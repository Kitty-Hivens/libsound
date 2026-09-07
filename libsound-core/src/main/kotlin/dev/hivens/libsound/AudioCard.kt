package dev.hivens.libsound

/**
 * Opaque handle for a card. A name rather than an index, for the same reason
 * [DeviceId] is one.
 */
@JvmInline
public value class CardId(public val value: String) {
    init {
        require(value.isNotBlank()) { "CardId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * One configuration a card can be put into.
 *
 * A profile decides which devices the card offers at all. Choosing one is not
 * routing: it is telling the machine what the hardware is being used as, and
 * the sinks and sources it currently exposes appear and disappear with the
 * choice.
 */
public data class CardProfile(
    /** The backend's name for it, and what [VolumeMixer.setCardProfile] takes. */
    public val name: String,
    /** Human-readable label, already localised where the system localises. */
    public val description: String,
    /**
     * The higher this is, the better a default the profile makes. Zero means
     * the profile exists but nothing is plugged into what it needs.
     */
    public val priority: Int = 0,
    /**
     * False when the machine knows activating it would achieve nothing. True
     * is not a guarantee that it will do something useful, only that nothing
     * known says otherwise.
     */
    public val available: Boolean = true,
)

/**
 * A sound card, and the configurations it can be put into.
 *
 * This is what pavucontrol's configuration tab is, and the case that justifies
 * carrying the type at all is a bluetooth headset: high quality playback and
 * the low quality mode that has a working microphone are two profiles of one
 * card, and no application could make that choice for the user who asked for
 * it until this existed.
 *
 * Behind [Capability.DEVICE_PROFILES]. A backend that cannot enumerate cards
 * reports an empty list rather than an exception, like every other query here.
 */
public data class AudioCard(
    public val id: CardId,
    /** Human-readable label, already localised where the system localises. */
    public val name: String,
    public val profiles: List<CardProfile> = emptyList(),
    /** Which of [profiles] is in use, by name, or null when the backend cannot tell. */
    public val activeProfile: String? = null,
)
