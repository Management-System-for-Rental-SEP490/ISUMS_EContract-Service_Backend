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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;

    @Value("${app.s3.bucket}")
    private String bucket;

    @Value("${app.s3.cloudfront-domain}")
    private String cloudFrontDomain;

    public String uploadCccdImage(MultipartFile file, UUID contractId, String side) {
        try {
            String ext = getExtension(file.getOriginalFilename());
            String key = "cccd/" + contractId + "/" + side + "." + ext;

            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(file.getContentType())
                            .contentLength(file.getSize())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            log.info("[CCCD] Uploaded {} key={}", side, key);
            return key;

        } catch (IOException e) {
            throw new RuntimeException("Failed to upload CCCD image: " + e.getMessage(), e);
        }
    }

    public String getImageUrl(String key) {
        return "https://" + cloudFrontDomain + "/" + key;
    }


    public byte[] downloadBytes(String key) {
        return s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build()
        ).asByteArray();
    }


    public byte[] appendCccdPage(byte[] contractPdf,
                                 byte[] frontImageBytes,
                                 byte[] backImageBytes) {
        try (PDDocument doc = Loader.loadPDF(contractPdf);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // Trang mới A4 ngang
            PDPage page = new PDPage(
                    new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
            doc.addPage(page);

            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();
            float margin = 30f;
            float gap = 20f;
            float imgWidth = (pageWidth - margin * 2 - gap) / 2;
            float imgHeight = pageHeight - margin * 2 - 60f;

            PDImageXObject frontImg = PDImageXObject.createFromByteArray(
                    doc, frontImageBytes, "front");
            PDImageXObject backImg = PDImageXObject.createFromByteArray(
                    doc, backImageBytes, "back");

            float frontScale = Math.min(
                    imgWidth / frontImg.getWidth(),
                    imgHeight / frontImg.getHeight());
            float backScale = Math.min(
                    imgWidth / backImg.getWidth(),
                    imgHeight / backImg.getHeight());

            float frontW = frontImg.getWidth() * frontScale;
            float frontH = frontImg.getHeight() * frontScale;
            float backW = backImg.getWidth() * backScale;
            float backH = backImg.getHeight() * backScale;
            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontNormal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float baseY = pageHeight - margin - 60f;

                cs.beginText();
                cs.setFont(fontBold, 13);
                cs.newLineAtOffset(margin, pageHeight - margin - 20);
                cs.showText("PHU LUC: CAN CUOC CONG DAN CUA NGUOI THUE");
                cs.endText();

                cs.drawImage(frontImg, margin, baseY - frontH, frontW, frontH);
                cs.beginText();
                cs.setFont(fontNormal, 10);
                cs.newLineAtOffset(margin + frontW / 2 - 25, baseY - frontH - 15);
                cs.showText("Mat truoc");
                cs.endText();

                float backX = margin + imgWidth + gap;
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
            log.error("[CCCD] appendCccdPage failed", e);
            throw new IllegalStateException("Failed to append CCCD to PDF", e);
        }
    }

    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "jpg";
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }
}