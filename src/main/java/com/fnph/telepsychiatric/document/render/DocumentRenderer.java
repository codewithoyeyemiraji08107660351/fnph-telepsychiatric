package com.fnph.telepsychiatric.document.render;

import com.fnph.telepsychiatric.clinical.FollowUp;
import com.fnph.telepsychiatric.clinical.Investigation;
import com.fnph.telepsychiatric.clinical.Prescription;
import com.fnph.telepsychiatric.document.QrCodeGenerator;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Renders a clinical document to PDF.
 *
 * <h2>HTML templates, not code</h2>
 *
 * A prescription layout changes when a hospital letterhead changes, and that
 * should not require a Java developer. The templates live in
 * {@code resources/templates/documents} and can be edited by anyone who can
 * read HTML.
 *
 * <h2>The QR code is embedded, not linked</h2>
 *
 * A printed page cannot fetch an image. It is generated at render time and
 * inlined as base64, so the PDF is self-contained and prints correctly from a
 * phone with no signal.
 *
 * <h2>Rendered once, at issue</h2>
 *
 * Not on every download. Re-rendering would let a document a patient printed in
 * March differ from the same document downloaded in June, and the issue number
 * would still say they were the same thing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy");

    private final TemplateEngine templateEngine;
    private final QrCodeGenerator qrCodeGenerator;

    public byte[] renderPrescription(Prescription prescription, String issueNumber,
                                     String verificationUrl, java.time.LocalDate expiresOn) {
        Context context = new Context();
        context.setVariable("issueNumber", issueNumber);
        context.setVariable("issuedOn", prescription.getIssueDate().format(DATE));
        context.setVariable("expiresOn", expiresOn == null ? null : expiresOn.format(DATE));
        context.setVariable("patientName", patientName(prescription));
        context.setVariable("patientIdentifier", patientIdentifier(prescription));
        context.setVariable("clinicalInformation", prescription.getClinicalInformation());
        context.setVariable("items", prescription.getItems());
        context.setVariable("verificationUrl", verificationUrl);
        context.setVariable("qrDataUri", qrDataUri(verificationUrl));
        return render("documents/prescription", context, issueNumber);
    }

    public byte[] renderInvestigation(Investigation investigation, String issueNumber,
                                      String verificationUrl, java.time.LocalDate expiresOn) {
        Context context = new Context();
        context.setVariable("issueNumber", issueNumber);
        context.setVariable("issuedOn", investigation.getIssueDate().format(DATE));
        context.setVariable("expiresOn", expiresOn == null ? null : expiresOn.format(DATE));
        context.setVariable("patientName", patientName(investigation));
        context.setVariable("patientIdentifier", patientIdentifier(investigation));
        context.setVariable("clinicalInformation", investigation.getClinicalInformation());
        context.setVariable("items", investigation.getItems());
        context.setVariable("verificationUrl", verificationUrl);
        context.setVariable("qrDataUri", qrDataUri(verificationUrl));
        return render("documents/investigation", context, issueNumber);
    }

    public byte[] renderFollowUp(FollowUp followUp, String issueNumber, String verificationUrl) {
        Context context = new Context();
        context.setVariable("issueNumber", issueNumber);
        context.setVariable("recommendation", followUp.getRecommendation());
        context.setVariable("reviewInterval", followUp.getReviewInterval());
        context.setVariable("preferredDate", followUp.getPreferredDate() == null
                ? null : followUp.getPreferredDate().format(DATE));
        context.setVariable("patientName", followUp.getPatient() == null ? "" :
                followUp.getPatient().getFirstName() + " " + followUp.getPatient().getLastName());
        context.setVariable("verificationUrl", verificationUrl);
        context.setVariable("qrDataUri", qrDataUri(verificationUrl));
        return render("documents/follow-up", context, issueNumber);
    }

    private byte[] render(String template, Context context, String issueNumber) {
        try {
            String html = templateEngine.process(template, context);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();

            byte[] pdf = out.toByteArray();
            log.info("Rendered {} to {} bytes", issueNumber, pdf.length);
            return pdf;
        } catch (Exception e) {
            // Never silently produce an empty document. A zero-byte prescription
            // that downloads successfully is worse than a failed download,
            // because the patient discovers it at the pharmacy counter.
            throw new IllegalStateException(
                    "Could not render " + issueNumber + ": " + e.getMessage(), e);
        }
    }

    private String qrDataUri(String verificationUrl) {
        return "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(qrCodeGenerator.generate(verificationUrl));
    }

    private String patientName(Prescription p) {
        if (p.getPatient() != null) {
            return p.getPatient().getFirstName() + " " + p.getPatient().getLastName();
        }
        return p.getCentrePatient() == null ? "" :
                p.getCentrePatient().getFirstName() + " " + p.getCentrePatient().getLastName();
    }

    private String patientName(Investigation i) {
        if (i.getPatient() != null) {
            return i.getPatient().getFirstName() + " " + i.getPatient().getLastName();
        }
        return i.getCentrePatient() == null ? "" :
                i.getCentrePatient().getFirstName() + " " + i.getCentrePatient().getLastName();
    }

    /** The EHR number for an FNPH patient, the centre-local one for a centre patient. */
    private String patientIdentifier(Prescription p) {
        if (p.getPatient() != null) {
            return p.getPatient().getEhrNumber();
        }
        return p.getCentrePatient() == null ? "" : p.getCentrePatient().getCentrePatientId();
    }

    private String patientIdentifier(Investigation i) {
        if (i.getPatient() != null) {
            return i.getPatient().getEhrNumber();
        }
        return i.getCentrePatient() == null ? "" : i.getCentrePatient().getCentrePatientId();
    }
}
