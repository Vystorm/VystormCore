package Vystorm.vystorm_core._advancedMethods.placeholderHandler;

/**
 * Answers a placeholder: gets the viewer, the key and its arguments and returns the text.
 * <p>
 * Return {@code null} for "I do not know this" or "not available here" (no viewer, viewer offline): the placeholder
 * then stays in the text as it was written. Return an empty string for a value that exists and is empty (no region,
 * no party). Any other object is turned into text with {@code String.valueOf}.
 * <p>
 * A resolver runs on the server thread unless its value was registered as async-safe; see
 * {@link PlaceholderInterface}. An exception is caught, reported and treated like {@code null}.
 *
 * @since 0.65.0
 */
@FunctionalInterface
public interface PlaceholderResolver {
    Object resolve(PlaceholderContext context);
}
