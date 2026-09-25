package com.mtdownloader.app

import android.view.View
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import java.io.File

class FilesPage(private val act: MainActivity, private val v: View) : Page {

    private val lv = v.findViewById<ListView>(R.id.lvFiles)
    private val tvDir = v.findViewById<TextView>(R.id.tvDir)
    private val btn = v.findViewById<Button>(R.id.btnRefresh)
    private val items = ArrayList<File>()

    override fun view() = v

    override fun onShow() {
        refresh()
    }

    init {
        lv.adapter = ArrayAdapter(act, android.R.layout.simple_list_item_1, items)
        btn.setOnClickListener { refresh() }
        tvDir.text = "保存位置：${act.storageDir.absolutePath}"
        refresh()
    }

    private fun refresh() {
        items.clear()
        val all = act.storageDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".mtprog") }
            ?.sortedBy { it.name }
        if (all != null) items.addAll(all)
        (lv.adapter as? BaseAdapter)?.notifyDataSetChanged()
        tvDir.text = "保存位置：${act.storageDir.absolutePath}\n共 ${items.size} 个文件"
    }
}
