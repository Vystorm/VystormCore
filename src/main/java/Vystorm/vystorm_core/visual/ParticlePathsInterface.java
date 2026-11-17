package Vystorm.vystorm_core.visual;

import java.util.ArrayList;
import java.util.List;

/**
 * Manager view of {@link ParticlePaths}, reached through {@code api.particle().paths()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ParticlePaths}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ParticlePathsInterface {
    /** The delegating default. Plugins use {@code api.particle().paths()}. */
    ParticlePathsInterface DEFAULT = new ParticlePathsInterface() {};

    /** See {@link ParticlePaths#line}. */
    default List<Point3> line(Point3 start, Point3 end, int count) { return ParticlePaths.line(start, end, count); }

    /** See {@link ParticlePaths#outline}. */
    default List<Point3> outline(ViewFrame frame, int pointsPerEdge) { return ParticlePaths.outline(frame, pointsPerEdge); }
}
