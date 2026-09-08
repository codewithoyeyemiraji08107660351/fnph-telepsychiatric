package com.fnph.telepsychiatric.document;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * QR codes, generated on demand.
 *
 * Not stored. A base64 PNG per document is dead weight, and the image is only a
 * rendering of the verification URL, which regenerating costs nothing.
 *
 * Error correction is set high because these are printed, folded, carried in a
 * pocket to a pharmacy and scanned under whatever light is available. A code
 * that stops scanning after a crease sends the patient back to the hospital.
 */
@Component
@Slf4j
public class QrCodeGenerator {

    private static final int SIZE = 300;

    public byte[] generate(String verificationUrl) {
        try {
            Map<EncodeHintType, Object> hints = Map.of(
                    // Roughly 30% of the code can be damaged and still read.
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H,
                    EncodeHintType.MARGIN, 2,
                    EncodeHintType.CHARACTER_SET, "UTF-8");

            BitMatrix matrix = new QRCodeWriter()
                    .encode(verificationUrl, BarcodeFormat.QR_CODE, SIZE, SIZE, hints);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate the QR code", e);
        }
    }
}
