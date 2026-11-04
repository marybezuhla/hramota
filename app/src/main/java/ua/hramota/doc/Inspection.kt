package ua.hramota.doc

/**
 * What a document carries besides its visible text: things that could be risky (never run or
 * fetched by Hramota) and things worth knowing before sending it on.
 */
data class Inspection(
    /** VBA macros (DOCM, or a DOCX renamed from one). Never run. */
    val macros: Boolean = false,
    /** Embedded files or objects, by name. Never opened by themselves. */
    val embedded: List<String> = emptyList(),
    /** Resources the file would load from the internet (remote template, linked picture). Never fetched. */
    val remoteResources: Int = 0,
    /** JavaScript actions in a PDF. Cannot run: the PDF engine has no JavaScript. */
    val javascript: Int = 0,
    /** Links that would start another program (PDF "Launch" actions). Never followed. */
    val launchActions: Int = 0,
    /** DDE fields, an old way to run commands from a document. Shown as text only. */
    val dde: Boolean = false,
    /** Links to web pages. Opened only after showing the real address. */
    val links: Int = 0,
    val trackedChanges: Boolean = false,
    val comments: Int = 0,
    val hiddenText: Boolean = false,
    val forms: Boolean = false,
    val author: String? = null,
    val lastModifiedBy: String? = null,
    /** The title written in the file's properties (offered for the shelf cover, never shown as a warning). */
    val title: String? = null,
) {
    /** Something that could do harm in another program. */
    val risky: Boolean get() = macros || embedded.isNotEmpty() || remoteResources > 0 || javascript > 0 || launchActions > 0 || dde

    /** Something worth knowing before sending the file on. */
    val notable: Boolean get() = risky || links > 0 || trackedChanges || comments > 0 || hiddenText || author != null || lastModifiedBy != null
}
