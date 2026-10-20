package com.lifolo.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.net.URI

/**
 * MinIO (S3 API, stands in for Cloudflare R2) for the R2 tests: one container for the test run, one bucket.
 * [register] points `lifolo.r2.*` at it (a separate Spring context — the other tests keep R2 off).
 */
object Minio {
    const val BUCKET = "lifolo-test"

    val container: MinIOContainer by lazy {
        MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2023-09-04T19-57-37Z")).apply { start() }
    }

    val s3: S3Client by lazy {
        S3Client.builder()
            .endpointOverride(URI.create(container.s3URL))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(container.userName, container.password)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .build()
            .also { c -> c.createBucket { it.bucket(BUCKET) } }
    }

    fun register(r: DynamicPropertyRegistry) {
        s3
        r.add("lifolo.r2.endpoint") { container.s3URL }
        r.add("lifolo.r2.access-key-id") { container.userName }
        r.add("lifolo.r2.secret-access-key") { container.password }
        r.add("lifolo.r2.bucket") { BUCKET }
        r.add("lifolo.r2.region") { "us-east-1" }
    }

    fun keys(): Set<String> = s3.listObjectsV2Paginator { it.bucket(BUCKET) }.contents().map { it.key() }.toSet()

    fun bytes(key: String): ByteArray? = try {
        s3.getObjectAsBytes { it.bucket(BUCKET).key(key) }.asByteArray()
    } catch (e: NoSuchKeyException) {
        null
    }

    fun contentType(key: String): String = s3.headObject { it.bucket(BUCKET).key(key) }.contentType()

    fun clear() {
        for (k in keys()) s3.deleteObject { it.bucket(BUCKET).key(k) }
    }
}
