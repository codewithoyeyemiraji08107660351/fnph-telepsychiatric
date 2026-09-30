package com.fnph.telepsychiatric.document.render;

import com.fnph.telepsychiatric.clinical.FollowUp;
import com.fnph.telepsychiatric.clinical.Investigation;
import com.fnph.telepsychiatric.clinical.Prescription;
import com.fnph.telepsychiatric.document.QrCodeGenerator;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Renders clinical documents to PDF.
 *
 * <h2>HTML templates, not code</h2>
 *
 * A prescription layout changes when a hospital letterhead changes, and that
 * should not require a Java developer. The templates live in
 * {@code resources/templates/documents} and can be edited as HTML.
 *
 * <h2>Images and QR codes are embedded</h2>
 *
 * PDF rendering must not depend on a browser, HTTP server, filesystem path,
 * or network connection being available. Therefore the QR code, hospital
 * logo, and doctor's signature are converted to Base64 data URIs before
 * Thymeleaf renders the HTML.
 *
 * <h2>Rendered once, at issue</h2>
 *
 * Documents are rendered at issue time rather than every time they are
 * downloaded. This keeps the issued document immutable.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentRenderer {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy");

    /*
     * These files live under:
     *
     * src/main/resources/static/fnph-logo.png
     * src/main/resources/static/WhatsApp Image 2026-09-29 at 21.12.55.jpeg
     *
     * Because they are packaged into the application JAR, they must be
     * accessed as classpath resources rather than Windows filesystem paths.
     */
    private static final String LOGO_RESOURCE =
            "static/fnph-logo.png";

    private static final String SIGNATURE_RESOURCE =
            "static/WhatsApp Image 2026-09-29 at 21.12.55.jpeg";

    private final TemplateEngine templateEngine;
    private final QrCodeGenerator qrCodeGenerator;

    /**
     * Render a prescription document.
     */
    public byte[] renderPrescription(
            Prescription prescription,
            String issueNumber,
            String verificationUrl,
            LocalDate expiresOn) {

        Context context = baseContext(
                issueNumber,
                verificationUrl
        );

        context.setVariable(
                "issuedOn",
                prescription.getIssueDate() == null
                        ? null
                        : prescription.getIssueDate().format(DATE)
        );

        context.setVariable(
                "expiresOn",
                expiresOn == null
                        ? null
                        : expiresOn.format(DATE)
        );

        context.setVariable(
                "patientName",
                patientName(prescription)
        );

        context.setVariable(
                "patientIdentifier",
                patientIdentifier(prescription)
        );

        context.setVariable(
                "clinicalInformation",
                prescription.getClinicalInformation()
        );

        context.setVariable(
                "items",
                prescription.getItems()
        );

        return render(
                "documents/prescription",
                context,
                issueNumber
        );
    }

    /**
     * Render an investigation document.
     */
    public byte[] renderInvestigation(
            Investigation investigation,
            String issueNumber,
            String verificationUrl,
            LocalDate expiresOn) {

        Context context = baseContext(
                issueNumber,
                verificationUrl
        );

        context.setVariable(
                "issuedOn",
                investigation.getIssueDate() == null
                        ? null
                        : investigation.getIssueDate().format(DATE)
        );

        context.setVariable(
                "expiresOn",
                expiresOn == null
                        ? null
                        : expiresOn.format(DATE)
        );

        context.setVariable(
                "patientName",
                patientName(investigation)
        );

        context.setVariable(
                "patientIdentifier",
                patientIdentifier(investigation)
        );

        context.setVariable(
                "clinicalInformation",
                investigation.getClinicalInformation()
        );

        context.setVariable(
                "items",
                investigation.getItems()
        );

        return render(
                "documents/investigation",
                context,
                issueNumber
        );
    }

    /**
     * Render a follow-up recommendation document.
     */
    public byte[] renderFollowUp(
            FollowUp followUp,
            String issueNumber,
            String verificationUrl) {

        Context context = baseContext(
                issueNumber,
                verificationUrl
        );

        context.setVariable(
                "recommendation",
                followUp.getRecommendation()
        );

        context.setVariable(
                "reviewInterval",
                followUp.getReviewInterval()
        );

        context.setVariable(
                "preferredDate",
                followUp.getPreferredDate() == null
                        ? null
                        : followUp.getPreferredDate().format(DATE)
        );

        context.setVariable(
                "patientName",
                followUp.getPatient() == null
                        ? ""
                        : followUp.getPatient().getFirstName()
                                + " "
                                + followUp.getPatient().getLastName()
        );

        return render(
                "documents/follow-up",
                context,
                issueNumber
        );
    }

    /**
     * Creates the common Thymeleaf context used by every clinical document.
     *
     * This is where the QR code, FNPH logo, and doctor's signature are
     * converted to self-contained data URIs.
     */
    private Context baseContext(
            String issueNumber,
            String verificationUrl) {

        Context context = new Context();

        context.setVariable(
                "issueNumber",
                issueNumber
        );

        context.setVariable(
                "verificationUrl",
                verificationUrl
        );

        /*
         * QR code
         */
        context.setVariable(
                "qrDataUri",
                qrDataUri(verificationUrl)
        );

        /*
         * FNPH hospital logo
         */
        context.setVariable(
                "logoDataUri",
                classpathImageAsDataUri(
                        LOGO_RESOURCE,
                        "image/png"
                )
        );

        /*
         * Consultant psychiatrist signature
         */
        context.setVariable(
                "signatureDataUri",
                classpathImageAsDataUri(
                        SIGNATURE_RESOURCE,
                        "image/jpeg"
                )
        );

        return context;
    }

    /**
     * Render Thymeleaf HTML into a PDF using OpenHTMLToPDF.
     */
    private byte[] render(
            String template,
            Context context,
            String issueNumber) {

        try {
            String html = templateEngine.process(
                    template,
                    context
            );

            ByteArrayOutputStream out =
                    new ByteArrayOutputStream();

            PdfRendererBuilder builder =
                    new PdfRendererBuilder();

            builder.useFastMode();

            /*
             * All images are embedded as data URIs, so no external
             * resource URL is required here.
             */
            builder.withHtmlContent(
                    html,
                    null
            );

            builder.toStream(out);

            builder.run();

            byte[] pdf = out.toByteArray();

            if (pdf.length == 0) {
                throw new IllegalStateException(
                        "PDF renderer produced an empty document"
                );
            }

            log.info(
                    "Rendered {} to {} bytes",
                    issueNumber,
                    pdf.length
            );

            return pdf;

        } catch (Exception e) {

            /*
             * Never silently produce an empty document.
             *
             * A zero-byte clinical document that appears to download
             * successfully is worse than a failed download because the
             * patient may only discover the problem at the pharmacy,
             * laboratory, or follow-up appointment.
             */
            throw new IllegalStateException(
                    "Could not render "
                            + issueNumber
                            + ": "
                            + e.getMessage(),
                    e
            );
        }
    }

    /**
     * Convert a generated QR code into a PNG data URI.
     */
    private String qrDataUri(String verificationUrl) {

        byte[] qrCode =
                qrCodeGenerator.generate(verificationUrl);

        return "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(qrCode);
    }

    /**
     * Load an image from the application classpath and convert it into
     * a Base64 data URI.
     *
     * This works both:
     *
     * - locally from the IDE
     * - from a Spring Boot executable JAR
     * - inside Docker
     * - inside Dokploy
     * - in production Linux containers
     *
     * It does NOT depend on a Windows filesystem path.
     */
    private String classpathImageAsDataUri(
            String resourcePath,
            String mimeType) {

        ClassPathResource resource =
                new ClassPathResource(resourcePath);

        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Required document image not found on classpath: "
                            + resourcePath
            );
        }

        try {
            byte[] bytes;

            try (var inputStream = resource.getInputStream()) {
                bytes = inputStream.readAllBytes();
            }

            if (bytes.length == 0) {
                throw new IllegalStateException(
                        "Document image is empty: "
                                + resourcePath
                );
            }

            return "data:"
                    + mimeType
                    + ";base64,"
                    + Base64.getEncoder().encodeToString(bytes);

        } catch (IOException e) {

            throw new IllegalStateException(
                    "Unable to load document image: "
                            + resourcePath,
                    e
            );
        }
    }

    /**
     * Resolve the patient name for an FNPH prescription.
     *
     * Falls back to centre patient information when this is a
     * centre-issued prescription.
     */
    private String patientName(Prescription prescription) {

        if (prescription.getPatient() != null) {
            return prescription.getPatient().getFirstName()
                    + " "
                    + prescription.getPatient().getLastName();
        }

        return prescription.getCentrePatient() == null
                ? ""
                : prescription.getCentrePatient().getFirstName()
                        + " "
                        + prescription.getCentrePatient().getLastName();
    }

    /**
     * Resolve the patient name for an FNPH investigation.
     */
    private String patientName(Investigation investigation) {

        if (investigation.getPatient() != null) {
            return investigation.getPatient().getFirstName()
                    + " "
                    + investigation.getPatient().getLastName();
        }

        return investigation.getCentrePatient() == null
                ? ""
                : investigation.getCentrePatient().getFirstName()
                        + " "
                        + investigation.getCentrePatient().getLastName();
    }

    /**
     * The EHR number for an FNPH patient, or the centre-local
     * patient identifier for a centre patient.
     */
    private String patientIdentifier(
            Prescription prescription) {

        if (prescription.getPatient() != null) {
            return prescription.getPatient().getEhrNumber();
        }

        return prescription.getCentrePatient() == null
                ? ""
                : prescription.getCentrePatient()
                        .getCentrePatientId();
    }

    /**
     * The EHR number for an FNPH patient, or the centre-local
     * patient identifier for a centre patient.
     */
    private String patientIdentifier(
            Investigation investigation) {

        if (investigation.getPatient() != null) {
            return investigation.getPatient().getEhrNumber();
        }

        return investigation.getCentrePatient() == null
                ? ""
                : investigation.getCentrePatient()
                        .getCentrePatientId();
    }
}