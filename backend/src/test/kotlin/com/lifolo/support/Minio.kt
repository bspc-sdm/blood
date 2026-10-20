package com.lifolo.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.containers.wait.strategy.Wait
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
 * An S3 server standing in for Cloudflare R2 in the R2 tests: one container for the test run, one bucket.
 * MinIO from quay.io (Docker Hub no longer has minio/minio); if that cannot be pulled, Adobe S3Mock.
 * [register] points `lifolo.r2.*` at it (a separate Spring context — the other tests keep R2 off).
 */
object Minio {
    const val BUCKET = "lifolo-test"

    private class Server(val url: String, val user: String, val password: String)

    private val server: Server by lazy {
        val log = LoggerFactory.getLogger(Minio::class.java)
        try {
            val image = DockerImageName.parse("quay.io/minio/minio:RELEASE.2024-12-18T13-15-44Z").asCompatibleSubstituteFor("minio/minio")
            val c = MinIOContainer(image).apply { start() }
            Server(c.s3URL, c.userName, c.password)
        } catch (e: Exception) {
            log.warn("MinIO not available ({}), using S3Mock", e.message)
            val c = GenericContainer(DockerImageName.parse("adobe/s3mock:3.12.0"))
                .withExposedPorts(9090)
                .waitingFor(Wait.forHttp("/").forPort(9090).forStatusCodeMatching { it < 500 })
            c.start()
            Server("http://${c.host}:${c.getMappedPort(9090)}", "test", "test-secret")
        }
    }

    val s3: S3Client by lazy {
        S3Client.builder()
            .endpointOverride(URI.create(server.url))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(server.user, server.password)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .build()
            .also { c -> c.createBucket { it.bucket(BUCKET) } }
    }

    fun register(r: DynamicPropertyRegistry) {
        s3
        r.add("lifolo.r2.endpoint") { server.url }
        r.add("lifolo.r2.access-key-id") { server.user }
        r.add("lifolo.r2.secret-access-key") { server.password }
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
