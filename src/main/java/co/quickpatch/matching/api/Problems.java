package co.quickpatch.matching.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

/** Errores del contrato ({@code application/problem+json}, RFC 9457) con {@code correlationId} (DD 8.1). */
public final class Problems {

    private static final String BASE = "https://quickpatch.internal/problems/";

    public static final URI VALIDATION = URI.create(BASE + "validacion");
    public static final URI UNAUTHENTICATED = URI.create(BASE + "no-autenticado");
    public static final URI FORBIDDEN = URI.create(BASE + "no-autorizado");
    public static final URI NOT_FOUND = URI.create(BASE + "no-encontrado");
    public static final URI INVALID_TRANSITION = URI.create(BASE + "transicion-invalida");
    public static final URI OFFER_NOT_AVAILABLE = URI.create(BASE + "oferta-no-disponible");
    public static final URI TECHNICIAN_NOT_AVAILABLE = URI.create(BASE + "tecnico-no-disponible");

    private Problems() {
    }

    public static ProblemDetail of(HttpServletRequest request, HttpStatus status, URI type, String title) {
        var problem = ProblemDetail.forStatus(status);
        problem.setType(type);
        problem.setTitle(title);
        problem.setProperty("correlationId", CorrelationIdFilter.get(request));
        return problem;
    }

    public static ProblemDetail validation(HttpServletRequest request, Map<String, List<String>> errors) {
        var problem = of(request, HttpStatus.BAD_REQUEST, VALIDATION, "La solicitud tiene datos inválidos");
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Escribe el problema directamente en la respuesta (filtros de seguridad, antes de llegar a los controladores). */
    public static void write(JsonMapper json, HttpServletRequest request, HttpServletResponse response,
            HttpStatus status, URI type, String title) throws IOException {
        var problem = of(request, status, type, title);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(problem));
    }
}
