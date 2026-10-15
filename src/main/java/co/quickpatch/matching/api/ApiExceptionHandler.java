package co.quickpatch.matching.api;

import co.quickpatch.matching.domain.matching.Matching.OfferNotAvailableException;
import co.quickpatch.matching.domain.matching.Matching.TechnicianNotAvailableException;
import co.quickpatch.matching.domain.technicians.Technicians.InvalidTransitionException;
import co.quickpatch.matching.domain.technicians.Technicians.TechnicianNotFoundException;
import co.quickpatch.matching.domain.technicians.Technicians.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Traduce las excepciones de los casos de uso a los errores del contrato. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    ProblemDetail validation(ValidationException e, HttpServletRequest request) {
        return Problems.validation(request, e.errors());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpServletRequest request) {
        return Problems.validation(request, Map.of("body", List.of("El cuerpo no es JSON válido para el contrato.")));
    }

    @ExceptionHandler(TechnicianNotFoundException.class)
    ProblemDetail notFound(TechnicianNotFoundException e, HttpServletRequest request) {
        var problem = Problems.of(request, HttpStatus.NOT_FOUND, Problems.NOT_FOUND, "El técnico no existe en Matching");
        problem.setDetail(e.getMessage() + " Intenta de nuevo en unos segundos.");
        return problem;
    }

    @ExceptionHandler(InvalidTransitionException.class)
    ProblemDetail invalidTransition(InvalidTransitionException e, HttpServletRequest request) {
        var problem = Problems.of(request, HttpStatus.CONFLICT, Problems.INVALID_TRANSITION,
                "El estado actual no admite el cambio");
        problem.setDetail(e.getMessage());
        return problem;
    }

    /** Un parámetro que no tiene el tipo del contrato, por ejemplo {@code page=a} o un {@code attemptId} que no es UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return Problems.validation(request, Map.of(e.getName(), List.of("El valor no tiene el formato esperado.")));
    }

    @ExceptionHandler(OfferNotAvailableException.class)
    ProblemDetail offerNotAvailable(OfferNotAvailableException e, HttpServletRequest request) {
        var problem = Problems.of(request, HttpStatus.CONFLICT, Problems.OFFER_NOT_AVAILABLE,
                "La oferta no está disponible");
        problem.setDetail("La oferta no existe, ya se respondió o venció.");
        return problem;
    }

    @ExceptionHandler(TechnicianNotAvailableException.class)
    ProblemDetail technicianNotAvailable(TechnicianNotAvailableException e, HttpServletRequest request) {
        var problem = Problems.of(request, HttpStatus.CONFLICT, Problems.TECHNICIAN_NOT_AVAILABLE,
                "No estás disponible para aceptar ofertas");
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(TechniciansController.InvalidTokenClaimsException.class)
    ProblemDetail invalidClaims(HttpServletRequest request) {
        return Problems.of(request, HttpStatus.UNAUTHORIZED, Problems.UNAUTHENTICATED,
                "El token no trae usuario ni tenant válidos");
    }
}
