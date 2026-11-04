package co.quickpatch.matching.api.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cabecera {@code X-Correlation-Id} del contrato: si llega un UUID válido se usa; si no, se genera uno. Se devuelve
 * en la respuesta y queda en el MDC de los logs. Corre antes que la seguridad, para que también los 401 la lleven.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    private static final String ATTRIBUTE = CorrelationIdFilter.class.getName();

    /** Correlación de la petición; un UUID nuevo si el filtro no corrió. */
    public static UUID get(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof UUID id ? id : UUID.randomUUID();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var correlationId = parse(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId.toString());
        MDC.put("correlationId", correlationId.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");
        }
    }

    private static UUID parse(String header) {
        if (header != null) {
            try {
                return UUID.fromString(header.trim());
            } catch (IllegalArgumentException e) {
                // Una cabecera que no es UUID se reemplaza por una nueva.
            }
        }
        return UUID.randomUUID();
    }
}
