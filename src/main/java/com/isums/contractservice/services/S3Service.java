package com.isums.contractservice.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;   // inject từ S3Config

    @Value("${app.s3.bucket}")
    private String bucket;

    @Value("${app.s3.cloudfront-domain}")
    private String cloudFrontDomain;

    public String uploadCccdImage(MultipartFile file, UUID contractId, String side) {
        try {
            String ext = extension(file.getOriginalFilename());
            String key = "cccd/" + contractId + "/" + side + "." + ext;
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket).key(key)
                            .contentType(file.getContentType())
                            .contentLength(file.getSize())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            log.info("[S3] CCCD uploaded key={}", key);
            return key;
        } catch (IOException e) {
            throw new RuntimeException("Upload CCCD thất bại: " + e.getMessage(), e);
        }
    }

    public String uploadContractPdf(byte[] pdfBytes, UUID contractId) {
        String key = "contracts/" + contractId + "/snapshot_" + System.currentTimeMillis() + ".pdf";
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket).key(key)
                        .contentType("application/pdf")
                        .contentLength((long) pdfBytes.length)
                        .build(),
                RequestBody.fromBytes(pdfBytes));
        log.info("[S3] Contract PDF uploaded key={}", key);
        return key;
    }

    public byte[] downloadBytes(String key) {
        return s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build()
        ).asByteArray();
    }

    public String presignedUrl(String key, int ttlMinutes) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(ttlMinutes))
                .getObjectRequest(r -> r.bucket(bucket).key(key))
                .build();
        return s3Presigner.presignGetObject(presignRequest)
                .url().toString();
    }

    public void deleteIfExists(String key) {
        if (key == null || key.isBlank()) return;
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            log.info("[S3] Deleted key={}", key);
        } catch (Exception e) {
            log.warn("[S3] Delete failed key={}: {}", key, e.getMessage());
        }
    }

    public String cloudFrontUrl(String key) {
        return "https://" + cloudFrontDomain + "/" + key;
    }

    public byte[] appendCccdPage(byte[] contractPdf,
                                 byte[] frontImageBytes,
                                 byte[] backImageBytes) {
        try (PDDocument doc = Loader.loadPDF(contractPdf);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // A4 landscape
            PDPage page = new PDPage(
                    new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
            doc.addPage(page);

            float pageW = page.getMediaBox().getWidth();
            float pageH = page.getMediaBox().getHeight();
            float margin = 30f;
            float gap = 20f;
            float imgW = (pageW - margin * 2 - gap) / 2;
            float imgH = pageH - margin * 2 - 60f;

            PDImageXObject frontImg = PDImageXObject.createFromByteArray(doc, frontImageBytes, "front");
            PDImageXObject backImg = PDImageXObject.createFromByteArray(doc, backImageBytes, "back");

            float frontScale = Math.min(imgW / frontImg.getWidth(), imgH / frontImg.getHeight());
            float backScale = Math.min(imgW / backImg.getWidth(), imgH / backImg.getHeight());
            float frontW = frontImg.getWidth() * frontScale, frontH = frontImg.getHeight() * frontScale;
            float backW = backImg.getWidth() * backScale, backH = backImg.getHeight() * backScale;

            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontNormal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float baseY = pageH - margin - 60f;

                cs.beginText();
                cs.setFont(fontBold, 13);
                cs.newLineAtOffset(margin, pageH - margin - 20);
                cs.showText("PHU LUC: CAN CUOC CONG DAN CUA NGUOI THUE");
                cs.endText();

                cs.drawImage(frontImg, margin, baseY - frontH, frontW, frontH);
                cs.beginText();
                cs.setFont(fontNormal, 10);
                cs.newLineAtOffset(margin + frontW / 2 - 25, baseY - frontH - 15);
                cs.showText("Mat truoc");
                cs.endText();

                float backX = margin + imgW + gap;
                cs.drawImage(backImg, backX, baseY - backH, backW, backH);
                cs.beginText();
                cs.setFont(fontNormal, 10);
                cs.newLineAtOffset(backX + backW / 2 - 20, baseY - backH - 15);
                cs.showText("Mat sau");
                cs.endText();
            }

            doc.save(out);
            return out.toByteArray();

        } catch (Exception e) {
            log.error("[S3] appendCccdPage failed", e);
            throw new IllegalStateException("Append CCCD page thất bại: " + e.getMessage(), e);
        }
    }

    public byte[] compressCccdImage(byte[] imageBytes) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (original == null) return imageBytes; // fallback nếu không đọc được

            int maxWidth = 1000;
            BufferedImage target;

            if (original.getWidth() > maxWidth) {
                int newHeight = (int) ((double) original.getHeight() / original.getWidth() * maxWidth);
                Image scaled = original.getScaledInstance(maxWidth, newHeight, Image.SCALE_SMOOTH);
                target = new BufferedImage(maxWidth, newHeight, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = target.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.drawImage(scaled, 0, 0, null);
                g2d.dispose();
            } else {
                target = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = target.createGraphics();
                g2d.drawImage(original, 0, 0, null);
                g2d.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.75f);
            writer.setOutput(ImageIO.createImageOutputStream(out));
            writer.write(null, new IIOImage(target, null, null), param);
            writer.dispose();

            byte[] compressed = out.toByteArray();
            log.info("[S3] CCCD compressed {}KB → {}KB",
                    imageBytes.length / 1024, compressed.length / 1024);
            return compressed;

        } catch (Exception e) {
            log.warn("[S3] CCCD compress failed, using original: {}", e.getMessage());
            return imageBytes; // fallback về ảnh gốc
        }
    }

    private String extension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "jpg";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}