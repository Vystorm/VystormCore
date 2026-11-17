package Vystorm.vystorm_core._advancedMethods.areaHandler;

/**
 * Minecraft coordinates: X/Z are horizontal; Y1/Y2 bound the vertical Y axis.
 * fullCircle ignores height; Cylinder and Rectangle include the inclusive Y1/Y2 height range.
 * A null world matches every world. Call AreaHandler.update after editing a registered area.
 */
public class Area {
    private AreaType areaType;
    private String NBT;
    private java.util.UUID world;

    private int CenterX;
    private int CenterZ;
    private int Radius;


    private int Corner1X;
    private int Corner1Z;
    private int Corner2X;
    private int Corner2Z;
    private int Y1;
    private int Y2;

    public void setAreaType(AreaType areaType) {
        this.areaType = areaType;
    }
    public AreaType getAreaType() {
        return areaType;
    }

    public int getCenterX() {
        return CenterX;
    }

    public void setCenterX(int centerX) {
        CenterX = centerX;
    }

    public int getCenterZ() {
        return CenterZ;
    }

    public void setCenterZ(int centerZ) {
        CenterZ = centerZ;
    }

    public int getRadius() {
        return Radius;
    }

    public void setRadius(int radius) {
        Radius = radius;
    }

    public int getCorner1X() {
        return Corner1X;
    }

    public void setCorner1X(int corner1X) {
        Corner1X = corner1X;
    }

    public int getCorner1Z() {
        return Corner1Z;
    }

    public void setCorner1Z(int corner1Z) {
        Corner1Z = corner1Z;
    }

    public int getCorner2X() {
        return Corner2X;
    }

    public void setCorner2X(int corner2X) {
        Corner2X = corner2X;
    }

    public int getCorner2Z() {
        return Corner2Z;
    }

    public void setCorner2Z(int corner2Z) {
        Corner2Z = corner2Z;
    }

    public int getY1() {
        return Y1;
    }

    public void setY1(int y1) {
        Y1 = y1;
    }

    public int getY2() {
        return Y2;
    }

    public void setY2(int y2) {
        Y2 = y2;
    }



    public String getNBT() { return NBT; }
    public void setNBT(String nbt) { NBT = nbt; }
    public java.util.UUID getWorld() { return world; }
    public void setWorld(java.util.UUID world) { this.world = world; }

    public boolean contains(org.bukkit.Location location) {
        validate();
        if (location == null || location.getWorld() == null) return false;
        if (world != null && !world.equals(location.getWorld().getUID())) return false;
        double x = location.getX(), horizontalZ = location.getZ(), height = location.getY();
        if (!Double.isFinite(x) || !Double.isFinite(horizontalZ) || !Double.isFinite(height)) return false;
        if (areaType != AreaType.fullCircle && (height < Math.min(Y1, Y2) || height > Math.max(Y1, Y2))) return false;
        if (areaType == AreaType.Rectangle) {
            return x >= Math.min(Corner1X, Corner2X) && x <= Math.max(Corner1X, Corner2X)
                    && horizontalZ >= Math.min(Corner1Z, Corner2Z) && horizontalZ <= Math.max(Corner1Z, Corner2Z);
        }
        double dx = x - CenterX, dz = horizontalZ - CenterZ;
        return dx * dx + dz * dz <= (double) Radius * Radius;
    }

    void validate() {
        java.util.Objects.requireNonNull(areaType, "areaType");
        if (areaType != AreaType.Rectangle && Radius < 0) throw new IllegalArgumentException("Radius must be non-negative");
    }

    Area copy() {
        Area copy = new Area();
        copy.areaType = areaType; copy.NBT = NBT; copy.world = world;
        copy.CenterX = CenterX; copy.CenterZ = CenterZ; copy.Radius = Radius;
        copy.Corner1X = Corner1X; copy.Corner1Z = Corner1Z;
        copy.Corner2X = Corner2X; copy.Corner2Z = Corner2Z;
        copy.Y1 = Y1; copy.Y2 = Y2;
        return copy;
    }
}
