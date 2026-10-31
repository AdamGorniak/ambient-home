package pl.ambienthome.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.roundToInt

/** Local photos only. Bounded decoding; imported originals are never retained. */
class WallAlbum(private val context: Context) {
    data class Photo(val id: String, val title: String, val credit: String, val source: String, val category: String)
    private val directory = File(context.filesDir, "photos").apply { mkdirs() }
    fun list(): List<Photo> {
        val array = JSONArray(context.assets.open("wallpapers.json").bufferedReader().use { it.readText() }.trimStart('\uFEFF'))
        val bundled = (0 until array.length()).map { i ->
            val p = array.getJSONObject(i)
            Photo(p.getString("id"), p.getString("title"), p.getString("credit"), p.getString("source"), p.getString("category"))
        }
        val imported = directory.listFiles()?.filter { it.name.endsWith(".jpg") && !it.name.endsWith(".thumb.jpg") }?.sortedBy { it.name }?.map {
            Photo("user:${it.nameWithoutExtension}", "Moja fotografia", "Własna kolekcja", "Plik zaimportowany do Ambient", "custom")
        }.orEmpty()
        return bundled + imported
    }
    private fun stream(photo: Photo, thumbnail: Boolean): InputStream {
        if (photo.id.startsWith("user:")) return File(directory, photo.id.removePrefix("user:") + if (thumbnail) ".thumb.jpg" else ".jpg").inputStream()
        return try { context.assets.open("${if (thumbnail) "thumbs" else "wallpapers"}/${photo.id}.jpg") }
        catch (_: Exception) { context.assets.open("wallpapers/${photo.id}.jpg") }
    }
    fun decode(photo: Photo, thumbnail: Boolean = false): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream(photo, thumbnail).use { BitmapFactory.decodeStream(it, null, bounds) }
        val limit = if (thumbnail) 240 else 1920
        var sample = 1
        while (bounds.outWidth / sample > limit * 2 || bounds.outHeight / sample > (if (thumbnail) 160 else 1200) * 2) sample *= 2
        stream(photo, thumbnail).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }) }
    } catch (_: Exception) { null }

    fun importPhoto(uri: Uri): Photo {
        require(list().count { it.category == "custom" } < 100) { "Album mieści do 100 własnych zdjęć" }
        val temp = File.createTempFile("import", ".tmp", context.cacheDir)
        val id = UUID.randomUUID().toString()
        val destination = File(directory, "$id.jpg")
        val thumbFile = File(directory, "$id.thumb.jpg")
        var bitmap: Bitmap? = null
        var rotated: Bitmap? = null
        var scaled: Bitmap? = null
        var thumbnail: Bitmap? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(16384); var size = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; size += n; require(size <= 40L * 1024 * 1024) { "Zdjęcie przekracza 40 MB" }; output.write(buffer, 0, n) }
            } } ?: error("Nie można odczytać zdjęcia")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Nieobsługiwane zdjęcie" }
            var sample = 1
            while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 4_200_000) sample *= 2
            bitmap = BitmapFactory.decodeFile(temp.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }) ?: error("Nie można otworzyć zdjęcia")
            val orientation = try { ExifInterface(temp.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Exception) { 1 }
            val matrix = Matrix().apply { when (orientation) {
                2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
            } }
            val original = bitmap
            rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            val upright = rotated
            val ratio = minOf(1f, 1920f / upright.width, 1080f / upright.height)
            scaled = Bitmap.createScaledBitmap(upright, (upright.width * ratio).roundToInt().coerceAtLeast(1), (upright.height * ratio).roundToInt().coerceAtLeast(1), true)
            destination.outputStream().use { require(scaled.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            val thumbRatio = minOf(1f, 240f / scaled.width, 160f / scaled.height)
            thumbnail = Bitmap.createScaledBitmap(scaled, (scaled.width * thumbRatio).roundToInt().coerceAtLeast(1), (scaled.height * thumbRatio).roundToInt().coerceAtLeast(1), true)
            thumbFile.outputStream().use { require(thumbnail.compress(Bitmap.CompressFormat.JPEG, 80, it)) }
            return Photo("user:$id", "Moja fotografia", "Własna kolekcja", "Plik zaimportowany do Ambient", "custom")
        } catch (error: Exception) { destination.delete(); thumbFile.delete(); throw error }
        finally { temp.delete(); listOfNotNull(bitmap, rotated, scaled, thumbnail).distinct().forEach { if (!it.isRecycled) it.recycle() } }
    }
    fun delete(photo: Photo) {
        if (!photo.id.startsWith("user:")) return
        val id = photo.id.removePrefix("user:")
        File(directory, "$id.jpg").delete(); File(directory, "$id.thumb.jpg").delete()
    }
}
