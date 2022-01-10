package com.jaq.web_scraper.chaldal

import com.github.doyaaaaaken.kotlincsv.client.CsvFileWriter
import com.github.doyaaaaaken.kotlincsv.client.CsvWriter
import com.github.doyaaaaaken.kotlincsv.client.KotlinCsvExperimental
import io.github.biezhi.webp.WebpIO
import kotlinx.serialization.ImplicitReflectionSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.stringify
import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.chrome.ChromeDriver
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.set


@OptIn(UnstableDefault::class)
@ImplicitReflectionSerializer
@ExperimentalStdlibApi
@KotlinCsvExperimental
fun main() {
    System.setProperty("webdriver.chrome.driver", "/home/jamshed/drivers/chromedriver")
    val processor = LinkProcessor("https://chaldal.com/")
    processor.maxWindow(1)
    processor.startScrapping()
}


@Serializable
data class Student(val name: String, val roll: Int)

@KotlinCsvExperimental
class LinkProcessor(val host: String) {
    private val processedLinks = mutableSetOf<String>()
    private val queuedLinks = mutableSetOf<String>()
    private val categoryLinks = hashMapOf<String, HashSet<String>>()
    private var maxWindowSize: Int = 1
    private var incrementEvery: Int = 100
    private var windows = mutableListOf<ChaldalScraper>()
    private val writer: CsvFileWriter
    private val checkpointFile = File("chaldal_scraper_ckpt.json")
    val productsCount = AtomicInteger(0)

    init {
        queuedLinks.add(host)
        windows.add(ChaldalScraper(this))
        val dataFileName = "chaldal_products.csv"
        writer = if (loadCheckpoint()) {
            CsvWriter().openAndGetRawWriter(dataFileName, true)
        } else {
            CsvWriter().openAndGetRawWriter(dataFileName).withHeader(ProductModel::class)
        }
    }

    @OptIn(UnstableDefault::class)
    private fun loadCheckpoint(): Boolean {
        if (checkpointFile.exists()) {
            try {
                val ckpt = Json.parse(CheckpointModel.serializer(), String(Files.readAllBytes(checkpointFile.toPath())))
                processedLinks.addAll(ckpt.processedLinks)
                queuedLinks.addAll(ckpt.queuedLinks)
                categoryLinks.putAll(ckpt.categoryLinks)
                productsCount.set(ckpt.productsCount)
                return true
            } catch (e: Exception) {
                println("Checkpoint loader causing errors")
            }
        }
        return false
    }


    @ImplicitReflectionSerializer
    private fun saveCheckpoint() {
        try {
            val ckpt = CheckpointModel(processedLinks, queuedLinks, categoryLinks, productsCount.get())
            Files.write(checkpointFile.toPath(), Json.stringify(ckpt).toByteArray())
        } catch (e: Exception) {
            println("Checkpoint saver causing errors")
        }
    }

    fun maxWindow(l: Int) {
        if (l > 1) maxWindowSize = l
    }

    @ImplicitReflectionSerializer
    fun startScrapping() {
        while (queuedLinks.size != 0) {
            updateScraper()
            windows.forEach {
                it.browseNext()
                it.processPage()
            }
            println("Link Processed : ${processedLinks.size}, Queued : ${queuedLinks.size}, Products Count : ${productsCount.get()}")
            saveCheckpoint()
        }
        writer.flush()
        writer.close()
    }

    private fun updateScraper() {
        if (maxWindowSize > windows.size && queuedLinks.size >= windows.size * incrementEvery) {
            windows.add(ChaldalScraper(this))
        }
    }

    fun writeProductRow(product: ProductModel) {
        writer.writeObjectRow(product)
    }


    fun nextLink(): String {
        if (queuedLinks.isNotEmpty()) {
            val lastQueue = queuedLinks.first()
            queuedLinks.remove(lastQueue)
            return lastQueue
        } else {
            windows.forEach { it.close() }
            return ""
        }
    }

    fun addToCategory(category: String, url: String) {
        if (categoryLinks.containsKey(category)) {
            categoryLinks[category]?.add(url)
        } else {
            categoryLinks[category] = hashSetOf(url)
        }
    }

    fun getCategory(url: String) = categoryLinks
        .filter { it.value.contains(url) }
        .map { it.key }.first()

    fun addToQueue(url: String) {
        if (url.startsWith(host) && !processedLinks.contains(url)) {
            queuedLinks.add(url)
        }
    }

    fun addProcessed(url: String) {
        processedLinks.add(url)
    }

    fun printCategories() {
        println(categoryLinks)
    }
}

@KotlinCsvExperimental
class ChaldalScraper(private val processor: LinkProcessor) {
    private val driver = ChromeDriver()
    private val jsExec = driver as JavascriptExecutor

    fun browseNext() {
        try {
            driver.get(processor.nextLink())
        } catch (e: Exception) {
            println("error on browsing url : ${driver.currentUrl}")
        }
    }

    fun processPage() {
        if (isProductDetailsPage()) {
            /*
            * gather product information's
            * name, category, image, price, shortDescription
            * */
            processProductDetails()

        } else {
            /*
            * reveal whole page using scrolling technique
            * */
            scrollToEnd()

            /*
            * find all anchor tag and add it to the queue
            * */
            val anchors = driver.findElementsByTagName("a")
            anchors.forEach {
                processor.addToQueue(it.getAttribute("href") ?: "")
                if (hasCategory()) {
                    val oList =
                        driver.findElements(By.ByXPath("/html/body/div[2]/div/div[5]/section/div/div/div/div/section/div[1]/ol/li"))
                    if (oList.isNotEmpty()) {
                        val category = oList.joinToString(" > ") { li -> li.text.replace("&", "&amp;") }
                        processor.addToCategory(category, it.getAttribute("href") ?: "")
                    }

                }
            }
            processor.addProcessed(driver.currentUrl)
        }
    }

    private fun processProductDetails() {
        /*
        * -----------------------------------------------------------
        * content xpaths
        * -----------------------------------------------------------
        * img      :- /html/body/div[2]/div/div[5]/section/div/div/div/div/div/section/div/article/section[1]/div/div/div[1]/img
        * name     :- /html/body/div[2]/div/div[5]/section/div/div/div/div/div/section/div/article/section[2]/div[1]/h1
        * weight   :- /html/body/div[2]/div/div[5]/section/div/div/div/div/div/section/div/article/section[2]/div[1]/span
        * price    :- /html/body/div[2]/div/div[5]/section/div/div/div/div/div/section/div/article/section[2]/div[2]/div/div[1]/span[2]/span
        *             /html/body/div[2]/div/div[5]/section/div/div/div/div/div/section/div/article/section[2]/div[2]/div/span[2]/span
        * desc     :- /html/body/div[2]/div/div[5]/section/div/div/div/div/div/section/div/article/section[2]/div[5]/p
        * category :-
        * -----------------------------------------------------------
        * */
        try {
            val imgElem =
                driver.findElement(By.ByXPath("/html/body/div[2]/div/div[5]/section/div/div/div/div/div/section/div/article/section[1]/div/div/div[1]/img"))
            val titleElem =
                driver.findElement(By.ByXPath("/html/body/div[2]/div/div[5]/section/div/div/div/div/div/section/div/article/section[2]/div[1]/h1"))
            val weightElem =
                driver.findElement(By.ByXPath("/html/body/div[2]/div/div[5]/section/div/div/div/div/div/section/div/article/section[2]/div[1]/span"))
            val priceElem =
                driver.findElements(By.ByXPath("/html/body/div[2]/div/div[5]/section/div/div/div/div/div/section/div/article/section[2]/div[2]/div/div[1]/span[2]/span"))
            val priceElem2 =
                driver.findElements(By.ByXPath("/html/body/div[2]/div/div[5]/section/div/div/div/div/div/section/div/article/section[2]/div[2]/div/span[2]/span"))
            val descElem =
                driver.findElements(By.ByXPath("/html/body/div[2]/div/div[5]/section/div/div/div/div/div/section/div/article/section[2]/div[5]/p"))
            val productName = titleElem.text
            val weight = weightElem.text
            val regularPrice =
                if (priceElem.isNotEmpty()) priceElem.first().text.toInt() else priceElem2.first().text.toInt()
            val shortDesc = if (descElem.isNotEmpty()) descElem.first().text else ""
            val category = processor.getCategory(driver.currentUrl)
            val fileName = saveImage(productName, imgElem.getAttribute("src"))
            processor.writeProductRow(ProductModel(fileName, productName, weight, category, regularPrice, shortDesc))
            processor.addProcessed(driver.currentUrl)
            processor.productsCount.set(processor.productsCount.get() + 1)
        } catch (e: Exception) {
            processor.addToQueue(driver.currentUrl)
        }
    }

    private fun hasCategory() = try {
        driver.findElement(By.ByXPath("/html/body/div[2]/div/div[5]/section/div/div/div/div/section/div[1]/ol"))
        true
    } catch (e: Exception) {
        false
    }

    private fun saveImage(title: String, imageUrl: String): String {
        val dir = File("images")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        var fileName = ""
        try {
            val url = URL(imageUrl)
            val urlConnection = url.openConnection()
            fileName = title.toLowerCase()
                .replace("(", " ")
                .replace(")", " ")
                .replace("±", " ")
                .replace("\"", " ")
                .replace("[ ]{2,}".toRegex(), " ")
                .trim()
                .replace(" ", "-")
            val fileCheck = File(dir, "$fileName.png")
            if (fileCheck.exists()) {
                fileName = "$fileName-1"
            }
            inputStream = urlConnection.getInputStream()
            outputStream = FileOutputStream(File(dir, "$fileName.webp"))
            val b = ByteArray(2048)
            var length: Int
            while (inputStream?.read(b).also { length = it!! } != -1) {
                outputStream.write(b, 0, length)
            }
        } catch (e: Exception) {
            println("Image Downloading error : ${e.message}")
        } finally {
            inputStream?.close()
            outputStream?.close()
        }
        try {
            val srcFile = File(dir, "$fileName.webp")
            val destFile = File(dir, "$fileName.png")
            WebpIO.create().toNormalImage(srcFile, destFile)
            srcFile.delete()
        } catch (e: Exception) {
            println("File Converting error : ${e.message}")
        }
        return "$fileName.png"
    }

    private fun scrollToEnd() {
        var windowHeight = jsExec.executeScript("return document.body.scrollHeight") as Long
        while (true) {
            jsExec.executeScript("window.scrollTo(0, document.body.scrollHeight)")
            val cWinHeight = jsExec.executeScript("return document.body.scrollHeight") as Long
            if (cWinHeight <= windowHeight) {
                break
            }
            windowHeight = cWinHeight
        }
    }

    private fun isProductDetailsPage() = try {
        driver.findElement(
            By.ByXPath(
                "/html/body/div[2]/div/div[5]/section/div/div/div/div/div/section/div/article/section[1]/div/div/div[1]/img"
            )
        )
        true
    } catch (e: Exception) {
        false
    }

    fun close() {
        driver.quit()
    }
}

data class ProductModel(
    val image: String,
    val name: String,
    val weight: String,
    val category: String,
    val price: Int,
    val desc: String
)

@Serializable
data class CheckpointModel(
    val processedLinks: MutableSet<String>,
    val queuedLinks: MutableSet<String>,
    val categoryLinks: HashMap<String, HashSet<String>>,
    val productsCount: Int
)