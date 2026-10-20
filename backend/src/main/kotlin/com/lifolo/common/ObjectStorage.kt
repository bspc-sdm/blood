package com.lifolo.common

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI

/**
 * File storage on Cloudflare R2 (S3 API). Phase 2 moves base64 photos (profile, licence documents,
 * lab report photos) here and keeps only keys in the database.
 */
interface ObjectStorage {
    val configured: Boolean
    fun put(key: String, bytes: ByteArray, contentType: String)
    fun get(key: String): ByteArray?
    fun delete(key: String)
}

@Configuration
class ObjectStorageConfig {
    @Bean
    fun objectStorage(props: LifoloProperties): ObjectStorage =
        if (props.r2.configured) R2Storage(props.r2) else UnconfiguredStorage()
}

class R2Storage(private val cfg: LifoloProperties.R2) : ObjectStorage {
    override val configured = true

    private val s3: S3Client by lazy {
        S3Client.builder()
            .endpointOverride(URI.create(cfg.resolvedEndpoint))
            // R2 ignores the region but the SDK needs one
            .region(Region.of("auto"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.accessKeyId, cfg.secretAccessKey)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
    }

    override fun put(key: String, bytes: ByteArray, contentType: String) {
        s3.putObject(PutObjectRequest.builder().bucket(cfg.bucket).key(key).contentType(contentType).build(), RequestBody.fromBytes(bytes))
    }

    override fun get(key: String): ByteArray? = try {
        s3.getObjectAsBytes(GetObjectRequest.builder().bucket(cfg.bucket).key(key).build()).asByteArray()
    } catch (e: NoSuchKeyException) {
        null
    }

    override fun delete(key: String) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(cfg.bucket).key(key).build())
    }
}

class UnconfiguredStorage : ObjectStorage {
    override val configured = false
    override fun put(key: String, bytes: ByteArray, contentType: String): Unit = throw IllegalStateException("R2 is not configured")
    override fun get(key: String): ByteArray? = null
    override fun delete(key: String) {}
}
