package Vystorm.vystorm_core.compat;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Item compatibility preserving the native format on modern servers. @since 0.48.0
 * <p>Preferred access: {@code api.compat().items()}.
 */
public final class ItemCompat {
    private static final int MAGIC = 0x56434931;
    private ItemCompat() {}

    public static String model(ItemMeta meta) {
        if (meta == null) return null;
        try {
            Object key = ItemMeta.class.getMethod("getItemModel").invoke(meta);
            return key == null ? null : key.toString();
        } catch (NoSuchMethodException unavailable) { return null; }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    public static byte[] serialize(ItemStack[] items) {
        try { return (byte[]) ItemStack.class.getMethod("serializeItemsAsBytes", ItemStack[].class).invoke(null, (Object) items); }
        catch (NoSuchMethodException unavailable) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeInt(MAGIC); out.writeInt(items.length);
                for (ItemStack item : items) {
                    byte[] data = item == null || PlatformCompat.isEmpty(item) ? new byte[0] : StorageCompat.serializeAsBytes(item);
                    out.writeInt(data.length); out.write(data);
                }
                return bytes.toByteArray();
            } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
        } catch (ReflectiveOperationException failure) { throw failure(failure); }
    }

    public static ItemStack[] deserialize(byte[] data) {
        if (!(data.length >= 4 && java.nio.ByteBuffer.wrap(data).getInt() == MAGIC)) {
            try { return (ItemStack[])ItemStack.class.getMethod("deserializeItemsFromBytes",byte[].class).invoke(null,(Object)data); }
            catch (NoSuchMethodException unavailable) { /* Read Paper's documented version-1 array frame on Bukkit. */ }
            catch (ReflectiveOperationException failure) { throw failure(failure); }
        }
        return deserializeFrame(data);
    }

    static ItemStack[] deserializeFrame(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            boolean frame = data.length >= 8 && in.readInt() == MAGIC;
            if (!frame) {
                in = new DataInputStream(new ByteArrayInputStream(data));
                if (in.readUnsignedByte() != 1) throw new IOException("Unsupported item array format");
            }
            {
                int count = in.readInt();
                if (count < 0 || count > 100000) throw new IOException("Invalid item count");
                ItemStack[] items = new ItemStack[count];
                for (int i = 0; i < count; i++) {
                    int length = in.readInt();
                    if (length < 0 || length > in.available()) throw new IOException("Invalid item length");
                    items[i] = length == 0 ? Vystorm.vystorm_core.compat.PlatformCompat.emptyItem() : StorageCompat.deserializeBytes(in.readNBytes(length));
                }
                if (in.available() != 0) throw new IOException("Trailing item data");
                return items;
            }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private static RuntimeException failure(ReflectiveOperationException failure) {
        if (failure instanceof InvocationTargetException target && target.getCause() instanceof RuntimeException cause) return cause;
        return new IllegalStateException(failure);
    }
}
