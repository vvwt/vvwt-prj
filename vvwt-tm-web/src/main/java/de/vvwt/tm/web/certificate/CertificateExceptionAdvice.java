package de.vvwt.tm.web.certificate;

import de.vvwt.tm.certificate.CertificateTemplateFormatException;
import de.vvwt.tm.certificate.CertificateTemplateSizeException;
import de.vvwt.tm.certificate.CertificateTemplateStorageException;
import de.vvwt.tm.tournament.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Exception advice for {@code de.vvwt.tm.certificate.*} domain exceptions thrown from the {@code
 * de.vvwt.tm.web.certificate} primary-adapter controller (E23S09, AC-ERROR-HANDLING-UNCHANGED).
 *
 * <p>The {@link de.vvwt.tm.tournament.internal.web.GlobalExceptionHandler} already covers {@code
 * de.vvwt.tm.web} as a base package (E22S07). However, it handles the legacy exception types from
 * {@code de.vvwt.tm.domain.certificate.*}. The new {@link de.vvwt.tm.certificate} Modulith module
 * introduces distinct exception FQNs ({@link CertificateTemplateFormatException}, {@link
 * CertificateTemplateSizeException}, {@link CertificateTemplateStorageException}); a separate
 * advice located in {@code de.vvwt.tm.web} is required to map those new types.
 *
 * <p>This is the exact analog of {@link de.vvwt.tm.web.photo.PhotoExceptionAdvice} (created in
 * E23S04) applied to the certificate module.
 *
 * <h2>Modulith boundary compliance</h2>
 *
 * <p>This advice resides in {@code de.vvwt.tm.web.certificate} — inside the {@code web} Modulith
 * module — which declares {@code allowedDependencies = {"certificate"}} per DEC-40 Clause A.
 * Importing from {@code de.vvwt.tm.certificate.*} is therefore within module-boundary rules. The
 * legacy {@code GlobalExceptionHandler} in {@code tournament.internal.web} cannot import from
 * {@code certificate} because {@code tournament.allowedDependencies = {"tenant"}} only.
 *
 * <h2>HTTP mapping (AC-ERROR-HANDLING-UNCHANGED, preserved verbatim from E13S01)</h2>
 *
 * <ul>
 *   <li>{@link CertificateTemplateFormatException} → 400 Bad Request
 *   <li>{@link CertificateTemplateSizeException} → 400 Bad Request
 *   <li>{@link CertificateTemplateStorageException} → 500 Internal Server Error
 * </ul>
 *
 * @see de.vvwt.tm.tournament.internal.web.GlobalExceptionHandler
 * @see CertificateTemplateController
 * @see de.vvwt.tm.web.photo.PhotoExceptionAdvice
 * @see DEC-40
 * @see E23S09
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
@ControllerAdvice(basePackages = {"de.vvwt.tm.web.certificate"})
public class CertificateExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(CertificateExceptionAdvice.class);

    /**
     * Maps {@link CertificateTemplateFormatException} to HTTP 400 Bad Request.
     *
     * <p>Preserves the HTTP mapping established in E13S01 for the legacy {@code
     * de.vvwt.tm.domain.certificate.CertificateTemplateFormatException} handler.
     */
    @ExceptionHandler(CertificateTemplateFormatException.class)
    public ResponseEntity<ApiErrorResponse> handleCertificateTemplateFormat(
            CertificateTemplateFormatException ex, HttpServletRequest request) {
        log.debug(
                "[tm-web] CertificateTemplateFormatException (certificate module): {}",
                ex.getMessage());
        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.BAD_REQUEST.value(),
                                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                                ex.getMessage(),
                                "error.certificateTemplate.format")
                        .path(request.getRequestURI())
                        .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Maps {@link CertificateTemplateSizeException} to HTTP 400 Bad Request.
     *
     * <p>Preserves the HTTP mapping established in E13S01 for the legacy {@code
     * de.vvwt.tm.domain.certificate.CertificateTemplateSizeException} handler.
     */
    @ExceptionHandler(CertificateTemplateSizeException.class)
    public ResponseEntity<ApiErrorResponse> handleCertificateTemplateSize(
            CertificateTemplateSizeException ex, HttpServletRequest request) {
        log.debug(
                "[tm-web] CertificateTemplateSizeException (certificate module): {}",
                ex.getMessage());
        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.BAD_REQUEST.value(),
                                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                                ex.getMessage(),
                                "error.certificateTemplate.tooLarge")
                        .path(request.getRequestURI())
                        .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Maps {@link CertificateTemplateStorageException} to HTTP 500 Internal Server Error.
     *
     * <p>Preserves the HTTP mapping established in E13S01 for the legacy {@code
     * de.vvwt.tm.domain.certificate.CertificateTemplateStorageException} handler.
     */
    @ExceptionHandler(CertificateTemplateStorageException.class)
    public ResponseEntity<ApiErrorResponse> handleCertificateTemplateStorage(
            CertificateTemplateStorageException ex, HttpServletRequest request) {
        log.error(
                "[tm-web] CertificateTemplateStorageException (certificate module): {}",
                ex.getMessage(),
                ex);
        ApiErrorResponse body =
                new ApiErrorResponse.Builder(
                                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                                "Certificate template storage error.",
                                "error.certificateTemplate.storage")
                        .path(request.getRequestURI())
                        .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
