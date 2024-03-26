package seamcarving
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.BufferedImage.TYPE_INT_RGB
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.properties.Delegates

typealias Point = Pair<Int, Int>

interface IPathCalculator{
    fun calculate(): Array<Point>
}

data class PixelInfo(val cost: Double, val backtrack: Point)

class DPPathcalculator(private val handler: ImageHandler): IPathCalculator{
    private val pixelDistances: MutableMap<Point, PixelInfo> = mutableMapOf()
    private val initialPoint = Point(0, -1)

    override fun calculate(): Array<Point> {
        dynamicProcessPixels()
        return buildShortestPath(initialPoint, Point(handler.image.width -1, handler.image.height))
    }

    private fun calcMinCost(pixel: Point): PixelInfo{
        if(pixel.second == 0 && pixel.first >= 0 && pixel.first < handler.image.width){
            return PixelInfo(getPixelEnergy(pixel), pixel)
        }

        if(pixel.first == -1 || pixel.first == handler.image.width){
            return PixelInfo(Double.MAX_VALUE, Point(Int.MAX_VALUE,Int.MAX_VALUE))
        }

        val p1 = Point(pixel.first - 1, pixel.second -1)
        val p2 = Point(pixel.first, pixel.second - 1)
        val p3 = Point(pixel.first + 1,     pixel.second -1)

        val v1 = pixelDistances.getOrElse(p1) {calcMinCost(p1)}
        val v2 = pixelDistances.getOrElse(p2) {calcMinCost(p2)}
        val v3 = pixelDistances.getOrElse(p3) {calcMinCost(p3)}

        val minVal = arrayOf(Pair(p1,v1),Pair(p2,v2),Pair(p3,v3)).minByOrNull { it.second.cost }

        val minCost: Double = minVal?.second?.cost ?: throw Exception("Min Cost Not Found")
        val minPixel: Point = minVal.first

        return PixelInfo( getPixelEnergy(pixel) + minCost, minPixel)
    }

    private fun dynamicProcessPixels(){
        for(y in 0 until  handler.image.height ){
            for(x in 0 until handler.image.width){
                pixelDistances[Point(x, y)] = pixelDistances.getOrElse(Point(x,y)) {calcMinCost(Point(x,y))}
            }
        }
    }

    private fun getPixelEnergy(pixel: Point): Double{
        return try {
            if(pixel.first < 0 || pixel.first > handler.image.width ) throw NeighborNotFoundException("Neighbor not found")
            when(pixel.second){
                -1 -> 0.0
                handler.image.height -> 0.0
                else -> handler.getPixelEnergy(pixel)
            }
        } catch (e: IndexOutOfBoundsException){
            throw NeighborNotFoundException("Neighbor not found")
        }
    }

    private fun buildShortestPath(from:Point, to: Point): Array<Point>{
        val path: MutableList<Point> = mutableListOf()
        var current: Point = pixelDistances.filterKeys { it.second == handler.image.height - 1 }.minByOrNull { it.value.cost }?.key ?: throw Exception("Could not define first point to build path")
        path.add(current)
        while (current.second != 0){
            val smallestNeighbor = pixelDistances[current]?.backtrack ?: throw Exception("Unable to Backtrack")
            path.add(smallestNeighbor)
            current = smallestNeighbor
        }
        return path.toTypedArray()
    }

}



class ImageHandler{

    private var width by Delegates.notNull<Int>()
    private var height by Delegates.notNull<Int>()

    lateinit var image: BufferedImage

    private lateinit var energyImage: BufferedImage
    private lateinit var imageName: String

    private var maxEnergy: Double = 0.0
    private var energyMatrix: MutableList<MutableList<Double>> = mutableListOf()
    private var pathStrategy: IPathCalculator = DPPathcalculator(this)



    fun reduce(inputImage:String, outputImage: String, widthToReduce: Int, heightToReduce: Int){
        imageName = outputImage
        readInputImage(inputImage)
        calculateImageEnergyMatrix()
        reduceImage(widthToReduce)
        //image = transposeImage(image)
        //reduceImage(heightToReduce)
        saveImage()
    }

    private fun reduceImage(times: Int){
        repeat(times){
            val pathToReduce = findSeam()
            executeImageReduction(pathToReduce)
            calculateImageEnergyMatrix()
        }
    }

    private fun executeImageReduction(path: Array<Point>, axis: Int = 0){
        val newImage = if(axis == 0) BufferedImage(image.width -1, image.height, image.type) else BufferedImage(image.width, image.height -1, image.type)
        for(y in 0 until image.height){
            var rgb = IntArray(image.width)
            rgb = image.getRGB(0,y, image.width, 1, rgb, 0, 1)
            val rgbList = rgb.toMutableList()
            rgbList.removeAt(path[y].first)
            newImage.setRGB(0,y, newImage.width, 1, rgbList.toIntArray(), 0, 1)
        }
        image = newImage
    }

    private fun transposeImage(refImage: BufferedImage): BufferedImage{
        val transposedImage = BufferedImage(refImage.height, refImage.width, refImage.type)

        for (x in 0 until transposedImage.width) {
            for (y in 0 until transposedImage.height) {
                transposedImage.setRGB(x,y, refImage.getRGB(y,x))
            }
        }

        return transposedImage
    }

    fun energyTransformation(inputImage:String, outputImage: String){
        imageName = outputImage
        readInputImage(inputImage)
        calculateImageEnergyMatrix()
        executeEnergyTransformation()
    }

    private fun findSeam(): Array<Point>{
        return pathStrategy.calculate()
    }

    private fun executeEnergyTransformation(){
        energyImage = image
        for(i in 0 until image.width){
            for(j in 0 until image.height){
                val intensity = (255.0 * energyMatrix[i][j]/ maxEnergy).toInt()
                val pixelColor = ImageColor(255, intensity, intensity, intensity)
                energyImage.setRGB(i,j, pixelColor.toInt()) // TODO: Change if need to transform the image again
            }
        }
    }

    private fun readInputImage(fileLocation: String){
        val imageFile = File(fileLocation)

        if(imageFile.exists()){
            image = ImageIO.read(imageFile)
        }
    }

    private fun calculateImageEnergyMatrix(){
        energyMatrix = mutableListOf()
        for(x in 0 until image.width){
            energyMatrix.add(mutableListOf())
            for(y in 0 until image.height){
                val energy = calculatePixelEnergy(x,y)
                if(energy >= maxEnergy) maxEnergy = energy
                energyMatrix[x].add(energy)
            }
        }
    }

    fun getPixelEnergy(point: Point): Double{
        return energyMatrix[point.first][point.second]
    }

    private fun calculatePixelEnergy(x: Int, y: Int): Double {
        val deltaX = calculateDeltaX(x,y)
        val deltaY = calculateDeltaY(x,y)

        return sqrt(deltaX + deltaY)
    }

    private fun calculateDeltaY(x: Int, y: Int): Double {
        val yCoord = when(y){
            0 -> 1
            image.height - 1 -> y - 1
            else -> y
        }

        val bottomPixelColor = readPixelColor(x, yCoord-1)
        val upperPixelColor = readPixelColor(x, yCoord+1)

        return calculateDiff(bottomPixelColor, upperPixelColor)
    }

    private fun calculateDeltaX(x: Int, y: Int): Double {
        val xCoord = when(x){
            0 -> 1
            image.width - 1 -> x - 1
            else -> x
        }

        val leftPixelColor = readPixelColor(xCoord - 1, y)
        val rightPixelColor = readPixelColor(xCoord + 1, y)

        return calculateDiff(leftPixelColor, rightPixelColor)
    }

    private fun calculateDiff(lPixelColor: ImageColor, rPixelColor: ImageColor): Double{
        val rDiff = (lPixelColor.red   -   rPixelColor.red).toDouble().pow(2)
        val gDiff = (lPixelColor.green -   rPixelColor.green).toDouble().pow(2)
        val bDiff = (lPixelColor.blue  -   rPixelColor.blue).toDouble().pow(2)

        return rDiff + gDiff + bDiff
    }

    private fun readPixelColor(x: Int, y: Int): ImageColor {
        val p: Int = image.getRGB(x, y)
        return intToColor(p)
    }

    private fun executeNegativeTransformation(){
        for(i in 0 until image.width){
            for(j in 0 until image.height){
                val p: Int = image.getRGB(i, j)
                val pixelColor = intToColor(p)
                pixelColor.invert()
                image.setRGB(i,j, pixelColor.toInt())
            }
        }
    }

    private fun intToColor(intColor: Int): ImageColor{
        val a = (intColor shr 24).and(255)
        val r = (intColor shr 16).and(255)
        val g = (intColor shr 8).and(255)
        val b = intColor.and(255)

        return ImageColor(a,r,g,b)
    }

    private fun createImage(){
        width = userIntInput("Enter rectangle width:")
        height = userIntInput("Enter rectangle height:")

        imageName = userImageNameInput()
        image = BufferedImage(width, height, TYPE_INT_RGB)
    }

    private fun paintDiagonals(){
        val graphics = image.graphics
        graphics.color = Color.RED

        graphics.drawLine(0,0, width-1, height-1)
        graphics.drawLine(0, height-1, width-1, 0)

    }

    private fun userImageNameInput():String{
        println("Enter output image name:")
        return readln().trim()
    }

    private fun userIntInput(message: String): Int{
        println(message)
        return readln().toInt()
    }

    private fun saveImage(){
        ImageIO.write(image, "png", File(imageName) );
    }
}

data class ImageColor(var a: Int, var red: Int, var green: Int, var blue: Int){

    fun invert(){
        red = 255 - red
        green = 255 - green
        blue = 255 - blue
    }

    fun toInt(): Int {
        return a.shl(24) + red.shl(16) + green.shl(8) + blue
    }
}

class NeighborNotFoundException(message: String): Exception(message)

fun main(args: Array<String>) {
    val imageHandler = ImageHandler()

    val inputImage = args[1]
    val outputImage = args[3]

    val widthToReduce = args[5].toInt()
    val heightToReduce = args[7].toInt()

    imageHandler.reduce(inputImage, outputImage, widthToReduce, heightToReduce)
}