package com.chanakya.shl2.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

@Service
public class QrCodeService {

    /**
     * Generates a QR code PNG image from the SHL URI.
     */
    public Mono<byte[]> generateQrCode(String shlUri, int size) {
        return Mono.fromCallable(() -> {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, 2
            );
            BitMatrix matrix = writer.encode(shlUri, BarcodeFormat.QR_CODE, size, size, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Generates a QR code as a data URI string.
     */
    public Mono<String> generateQrCodeDataUri(String shlUri, int size) {
        return generateQrCode(shlUri, size)
                .map(bytes -> "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes));
    }
}
