package com.lifolo.notify

import com.lifolo.common.LifoloProperties
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sesv2.SesV2Client
import software.amazon.awssdk.services.sesv2.model.Body
import software.amazon.awssdk.services.sesv2.model.Content
import software.amazon.awssdk.services.sesv2.model.Destination
import software.amazon.awssdk.services.sesv2.model.EmailContent
import software.amazon.awssdk.services.sesv2.model.Message
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest

/** Outgoing email (Node used Gmail via nodemailer; here Amazon SES). */
interface MailSender {
    fun send(to: String, subject: String, text: String, html: String)
}

/** The login-code email, same wording as email-code.js sendCodeEmail */
object LoginCodeMail {
    fun subject(code: String) = "আপনার লগইন কোড: $code"

    fun text(code: String) =
        "Lifolo অ্যাপে লগইনের কোড: $code\n\n" +
            "কোডটা ১০ মিনিট চলবে। আপনি কোড না চেয়ে থাকলে এই ইমেইল উপেক্ষা করুন, কাউকে কোডটা দেবেন না।"

    fun html(code: String) =
        "<p>Lifolo অ্যাপে লগইনের কোড:</p><p style=\"font-size:28px;font-weight:bold;letter-spacing:6px\">$code</p>" +
            "<p>কোডটা ১০ মিনিট চলবে। আপনি কোড না চেয়ে থাকলে এই ইমেইল উপেক্ষা করুন, কাউকে কোডটা দেবেন না।</p>"
}

@Configuration
class MailConfig {
    @Bean
    fun mailSender(props: LifoloProperties): MailSender =
        if (props.ses.configured) SesMailSender(props.ses) else LoggingMailSender().also {
            LoggerFactory.getLogger(MailConfig::class.java).warn("Mail: logging only (SES_REGION / SES_FROM not set)")
        }
}

class SesMailSender(private val cfg: LifoloProperties.Ses) : MailSender {
    private val client: SesV2Client by lazy {
        val creds = if (cfg.accessKeyId.isNotBlank() && cfg.secretAccessKey.isNotBlank()) {
            StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.accessKeyId, cfg.secretAccessKey))
        } else {
            DefaultCredentialsProvider.builder().build()
        }
        SesV2Client.builder().region(Region.of(cfg.region)).credentialsProvider(creds).build()
    }

    init {
        // build the client at start-up (no request is sent), so a bad region/credential setup shows in the log at once;
        // the deploy smoke test looks for this line
        val log = LoggerFactory.getLogger(SesMailSender::class.java)
        try {
            client.serviceName()
            log.info("Mail: Amazon SES client ready (region {}, from {})", cfg.region, cfg.from)
        } catch (e: Exception) {
            log.error("Mail: Amazon SES client failed to initialise: {}", e.message)
        }
    }

    override fun send(to: String, subject: String, text: String, html: String) {
        val utf8 = { s: String -> Content.builder().data(s).charset("UTF-8").build() }
        client.sendEmail(
            SendEmailRequest.builder()
                .fromEmailAddress(cfg.from)
                .destination(Destination.builder().toAddresses(to).build())
                .content(
                    EmailContent.builder().simple(
                        Message.builder()
                            .subject(utf8(subject))
                            .body(Body.builder().text(utf8(text)).html(utf8(html)).build())
                            .build(),
                    ).build(),
                )
                .build(),
        )
    }
}

/** Development stub when SES is not configured: logs instead of sending. Never leave it like this in production. */
class LoggingMailSender : MailSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(to: String, subject: String, text: String, html: String) {
        log.warn("SES not configured — email to {} not sent. Subject: {}", to, subject)
    }
}
