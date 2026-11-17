package Vystorm.vystorm_core.item;

import java.util.UUID;

/**
 * Verlustfreie Lager-Repräsentation (Deep Inventory, Markt): vollständige Itemdaten ({@code data}, Base64 des
 * serialisierten ItemStacks) plus Identität für Stack- und Duplikatregeln.
 */
public record StoredItem(int schemaVersion, String templateId, UUID instanceId, String fingerprint, int amount, String data) {}
