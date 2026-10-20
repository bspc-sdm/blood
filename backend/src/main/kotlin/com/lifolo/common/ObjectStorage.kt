package com.lifolo.common

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
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
 * File storage on Cloudflare R2 (S3 API), private bucket (no public URLs: the server reads the bytes and answers
 * the API like before). Used through [StoredFiles], which keeps the reference rows; see V602__r2_files.sql.
 * [configured] = all R2_* env vars are set; otherwise [UnconfiguredStorage] and every photo stays base64 in
 * PostgreSQL (the behaviour before R2).
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
            // R2 ignores the region ("auto") but the SDK needs one; MinIO in the tests wants us-east-1
            .region(Region.of(cfg.region.ifBlank { "auto" }))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.accessKeyId, cfg.secretAccessKey)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            // no default CRC trailers (aws-chunked): R2 and MinIO both accept plain uploads; integrity is checked
            // with our own SHA-256 (StoredFiles / StorageMigrator)
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
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
