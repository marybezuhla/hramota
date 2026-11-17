package ua.hramota.doc.sheet.edit

import ua.hramota.doc.sheet.Workbook
import java.io.InputStream
import java.io.OutputStream

/** A workbook's state while editing, kept to undo a change of rows (whatever the editor keeps). */
interface SheetSnapshot

/**
 * A workbook opened for editing cells: an Excel workbook ([XlsxEdit]) or an OpenDocument
 * spreadsheet ([ua.hramota.doc.odf.OdsEdit]). The editor works the same on either; [full] says
 * whether looks, widths, rows and columns, sorting and notes can be changed too.
 */
interface SheetEdit {
    /** One sheet of the file: its name and whether it is shown. */
    class SheetName(val name: String, val visible: Boolean)

    /** The file's sheets, in order. */
    val sheetNames: List<SheetName>
    /** Looks, widths, rows and columns, sorting and notes can be changed (not only what cells hold). */
    val full: Boolean get() = true
    /** The file's extension, as saved. */
    val extension: String

    /** What the person changed, cell by cell (a copy). */
    val changes: Map<CellKey, Input>
    /** Puts [input] into a cell (null: back to what the file has). */
    fun set(cell: CellKey, input: Input?)
    /** True when the cell cannot be changed here. */
    fun locked(cell: CellKey): Boolean
    /** What the cell holds, as the person would type it. */
    fun content(cell: CellKey, decimalComma: Boolean = true): String
    /** What the cell holds exactly (for moving it whole); null for what is not moved. */
    fun stored(cell: CellKey): Input?
    /** The cell's style as the file has it, as an index into the shown workbook's styles; null if unknown. */
    fun style(cell: CellKey): Int?
    /** What was typed, as a spreadsheet takes it; null when it cannot be taken. */
    fun parse(typed: String, dateCell: Boolean, date1904: Boolean = false): Input?
    /** The values of the cells that changed and of the formulas that depend on them. */
    fun recalc(): Map<CellKey, Value>

    val noteChanges: Map<CellKey, String>
    fun setNote(cell: CellKey, text: String?)
    fun canNote(sheet: Int): Boolean
    val lookChanges: Map<CellKey, CellLook>
    val widthChanges: Map<Pair<Int, Int>, Float>
    fun setLook(cell: CellKey, look: CellLook?): Boolean
    fun look(cell: CellKey): CellLook?
    fun setWidth(sheet: Int, col: Int, chars: Float?)

    /** About how much the workbook holds (undo of row changes keeps copies of it). */
    val size: Long
    fun snapshot(): SheetSnapshot
    fun restore(s: SheetSnapshot)
    fun moveRows(sheet: Int, at: Int, n: Int): XlsxEdit.RowRefusal?
    fun moveColumns(sheet: Int, at: Int, n: Int): XlsxEdit.RowRefusal?

    /** Writes the edited workbook: the file is read again from [source] and copied with the changes. */
    fun save(source: InputStream, out: OutputStream)
    /** Reads a workbook of this kind (to check a saved one). */
    fun readBack(input: InputStream): Workbook
}
