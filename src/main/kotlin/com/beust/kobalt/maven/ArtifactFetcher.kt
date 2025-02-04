package com.beust.kobalt.maven

import com.beust.kobalt.file
import com.beust.kobalt.misc.KFiles
import com.beust.kobalt.misc.KobaltLogger
import com.beust.kobalt.plugin.packaging.JarUtils
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.google.inject.assistedinject.Assisted
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadManager @Inject constructor(val factory: ArtifactFetcher.IFactory) {
    class Key(val url: String, val fileName: String, val executor: ExecutorService) {
        override fun equals(other: Any?): Boolean {
            return (other as Key).url == url
        }

        override fun hashCode(): Int {
            return url.hashCode()
        }
    }

    private val CACHE : LoadingCache<Key, Future<File>> = CacheBuilder.newBuilder()
        .build(object : CacheLoader<Key, Future<File>>() {
            override fun load(key: Key): Future<File> {
                return key.executor.submit(factory.create(key.url, key.fileName))
            }
        })

    public fun download(url: String, fileName: String, executor: ExecutorService)
            : Future<File> = CACHE.get(Key(url, fileName, executor))
}

/**
 * Fetches an artifact (a file in a Maven repo, .jar, -javadoc.jar, ...) to the given local file.
 */
class ArtifactFetcher @Inject constructor(@Assisted("url") val url: String,
        @Assisted("fileName") val fileName: String,
        val files: KFiles, val http: Http) : Callable<File>, KobaltLogger {
    interface IFactory {
        fun create(@Assisted("url") url: String, @Assisted("fileName") fileName: String) : ArtifactFetcher
    }

    /** The Kotlin compiler is about 17M and downloading it with the default buffer size takes forever */
    private val estimatedSize: Int
        get() = if (url.contains("kotlin-compiler")) 18000000 else 1000000

    private fun toMd5(bytes: ByteArray) : String {
        val result = StringBuilder()
        val md5 = MessageDigest.getInstance("MD5").digest(bytes)
        md5.forEach {
            val byte = it.toInt() and 0xff
            if (byte < 16) result.append("0")
            result.append(Integer.toHexString(byte))
        }
        return result.toString()
    }

    private fun getBytes(url: String) : ByteArray {
        log(2, "${url}: downloading to ${fileName}")
        val body = http.get(url)
        if (body.code == 200) {
            val buffer = ByteArrayOutputStream(estimatedSize)
            body.getAsStream().copyTo(buffer, estimatedSize)
            return buffer.toByteArray()
        } else {
            throw KobaltException("${url}: failed to download, code: ${body.code}")
        }
    }

    override fun call() : File {
        val md5Body = http.get(url + ".md5")
        val remoteMd5 = if (md5Body.code == 200) {
            md5Body.getAsString().trim(' ', '\t', '\n').substring(0, 32)
        } else {
            null
        }

        val file = File(fileName)
        file.parentFile.mkdirs()
        val bytes = getBytes(url)
        if (remoteMd5 != null && remoteMd5 != toMd5(bytes)) {
            throw KobaltException("MD5 not matching for ${url}")
        } else {
            log(2, "No md5 found for ${url}, skipping md5 check")
        }
        files.saveFile(file, bytes)

        log(1, "Downloaded $url")

        return file
    }
}
