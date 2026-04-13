package com.isums.contractservice.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("S3Service")
class S3ServiceTest {

    @Mock private S3Client s3Client;
    @Mock private S3Presigner s3Presigner;

    @InjectMocks private S3Service service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "bucket", "test-bucket");
        ReflectionTestUtils.setField(service, "cloudFrontDomain", "cdn.example.com");
    }

    @Nested
    @DisplayName("uploadCccdImage")
    class UploadCccd {

        @Test
        @DisplayName("uploads with cccd/<contractId>/<side>.<ext> key")
        void uploads() {
            UUID contractId = UUID.randomUUID();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "cccd.JPG", "image/jpeg",
                    "payload".getBytes(StandardCharsets.UTF_8));

            String key = service.uploadCccdImage(file, contractId, "front");

            assertThat(key).isEqualTo("cccd/" + contractId + "/front.jpg");

            ArgumentCaptor<PutObjectRequest> cap = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(cap.capture(), any(RequestBody.class));
            assertThat(cap.getValue().bucket()).isEqualTo("test-bucket");
            assertThat(cap.getValue().contentType()).isEqualTo("image/jpeg");
        }

        @Test
        @DisplayName("defaults extension to 'jpg' when filename has no extension")
        void noExtension() {
            UUID contractId = UUID.randomUUID();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "noext", "image/jpeg", new byte[]{1});

            String key = service.uploadCccdImage(file, contractId, "back");
            assertThat(key).endsWith("back.jpg");
        }

        @Test
        @DisplayName("wraps IOException into RuntimeException")
        void ioException() {
            MultipartFile broken = new BrokenMultipartFile();

            assertThatThrownBy(() -> service.uploadCccdImage(broken, UUID.randomUUID(), "front"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Upload CCCD thất bại");
        }
    }

    @Nested
    @DisplayName("uploadContractPdf")
    class UploadContractPdf {

        @Test
        @DisplayName("uploads contract/<id>/snapshot_<ts>.pdf")
        void uploads() {
            UUID contractId = UUID.randomUUID();
            byte[] pdfBytes = "PDFSNAPSHOT".getBytes(StandardCharsets.UTF_8);

            String key = service.uploadContractPdf(pdfBytes, contractId);

            assertThat(key).startsWith("contracts/" + contractId + "/snapshot_");
            assertThat(key).endsWith(".pdf");

            ArgumentCaptor<PutObjectRequest> cap = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(cap.capture(), any(RequestBody.class));
            assertThat(cap.getValue().contentType()).isEqualTo("application/pdf");
            assertThat(cap.getValue().contentLength()).isEqualTo(pdfBytes.length);
        }
    }

    @Nested
    @DisplayName("downloadBytes")
    class Download {

        @Test
        @DisplayName("returns bytes from S3")
        void returnsBytes() {
            byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
            ResponseBytes<GetObjectResponse> responseBytes =
                    ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), payload);
            when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

            assertThat(service.downloadBytes("k")).isEqualTo(payload);
        }
    }

    @Nested
    @DisplayName("deleteIfExists")
    class Delete {

        @Test
        @DisplayName("no-op when key null or blank")
        void noOp() {
            service.deleteIfExists(null);
            service.deleteIfExists("");
            service.deleteIfExists("   ");
        }

        @Test
        @DisplayName("deletes object when key provided")
        void deletes() {
            service.deleteIfExists("k1");

            ArgumentCaptor<DeleteObjectRequest> cap = ArgumentCaptor.forClass(DeleteObjectRequest.class);
            verify(s3Client).deleteObject(cap.capture());
            assertThat(cap.getValue().key()).isEqualTo("k1");
            assertThat(cap.getValue().bucket()).isEqualTo("test-bucket");
        }

        @Test
        @DisplayName("swallows S3 errors so callers are not broken")
        void swallowsError() {
            doThrow(AwsServiceException.builder().message("boom").build())
                    .when(s3Client).deleteObject(any(DeleteObjectRequest.class));

            service.deleteIfExists("k1");
        }
    }

    @Nested
    @DisplayName("cloudFrontUrl")
    class CloudFrontUrl {

        @Test
        @DisplayName("builds CDN URL with key")
        void builds() {
            assertThat(service.cloudFrontUrl("cccd/x/front.jpg"))
                    .isEqualTo("https://cdn.example.com/cccd/x/front.jpg");
        }
    }

    private static class BrokenMultipartFile implements MultipartFile {
        @Override public String getName() { return "broken"; }
        @Override public String getOriginalFilename() { return "x.jpg"; }
        @Override public String getContentType() { return "image/jpeg"; }
        @Override public boolean isEmpty() { return false; }
        @Override public long getSize() { return 1L; }
        @Override public byte[] getBytes() { return new byte[]{1}; }
        @Override public InputStream getInputStream() throws IOException {
            throw new IOException("simulated");
        }
        @Override public void transferTo(java.io.File dest) {}
    }
}
