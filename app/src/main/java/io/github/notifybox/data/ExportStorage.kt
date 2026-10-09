package io.github.notifybox.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExportStorage {
    fun available(context: Context, tree: String?): Boolean = tree != null &&
        context.contentResolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isWritePermission }

    fun label(context: Context, tree: String?): String {
        if (tree == null) return "未设置"
        return runCatching {
            val uri = Uri.parse(tree)
            val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "已选择的文件夹"
        }.getOrDefault("已选择的文件夹")
    }

    suspend fun export(context: Context, repository: Repository) {
        val value = repository.exportTree()
        require(available(context, value)) { "请先设置可用的导出文件夹" }
        val tree = Uri.parse(value!!)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val date = SimpleDateFormat("yyyy-MM-dd HH_mm_ss", Locale.CHINA).format(Date())
        // 先准备内容，再创建文件；createDocument 不覆盖已有文件。
        val data = repository.export().toByteArray(Charsets.UTF_8)
        val document = DocumentsContract.createDocument(context.contentResolver, parent, "application/json", "NotifyBox_exportConfig_$date.json")
            ?: throw IllegalArgumentException("无法创建文件，请重新选择导出文件夹")
        try {
            context.contentResolver.openOutputStream(document, "wt")?.use { it.write(data) }
                ?: error("文件写入失败")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, document) }
            throw e
        }
    }
}
