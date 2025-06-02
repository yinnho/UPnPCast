package com.yinnho.upnpcast.demo.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yinnho.upnpcast.DLNACast
import com.yinnho.upnpcast.demo.R
import android.util.Log

/**
 * 设备列表适配器 - 适配新的DLNADevice模型
 * 展示发现的DLNA设备
 */
class DeviceListAdapter(private val onDeviceClick: (DLNACast.Device) -> Unit) : 
    RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {
    
    companion object {
        private const val TAG = "DeviceListAdapter"
    }
    
    private var devices: List<DLNACast.Device> = emptyList()
    
    /**
     * 更新设备列表
     * @param newDevices 新的设备列表
     */
    fun updateDevices(newDevices: List<DLNACast.Device>) {
        Log.d(TAG, "收到设备列表更新: ${newDevices.size}个设备")
        newDevices.forEachIndexed { index, device ->
            Log.d(TAG, "收到设备[$index]: ${device.name}, ID: ${device.id}")
        }
        
        devices = newDevices
        notifyDataSetChanged()
        Log.d(TAG, "设备列表已更新，当前有${devices.size}个设备")
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.bind(device)
        holder.itemView.setOnClickListener { onDeviceClick(device) }
    }
    
    override fun getItemCount() = devices.size
    
    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val deviceName: TextView = itemView.findViewById(R.id.device_name)
        private val deviceManufacturer: TextView = itemView.findViewById(R.id.device_manufacturer)
        private val deviceIpPort: TextView = itemView.findViewById(R.id.device_ip_port)
        private val deviceUdn: TextView = itemView.findViewById(R.id.device_udn)
        
        /**
         * 绑定设备数据到视图
         * @param device 要展示的设备
         */
        fun bind(device: DLNACast.Device) {
            try {
                // 设备名称 + 类型标识
                deviceName.text = "${device.name} ${getDeviceTypeIcon(device)}"
                
                // 设备地址信息
                deviceManufacturer.text = "地址: ${device.address}"
                
                // IP地址（简化显示）
                deviceIpPort.text = "ID: ${device.id}"
                
                // 设备类型
                val statusText = if (device.isTV) "类型: 智能电视" else "类型: 媒体设备"
                deviceUdn.text = statusText
                
                // 根据设备类型设置不同的样式
                setDeviceTypeStyle(device)
                
                Log.d(TAG, "设备详情 - ${device.name}, 类型: ${if (device.isTV) "电视" else "设备"}")
            } catch (e: Exception) {
                Log.e(TAG, "绑定设备数据时出错", e)
                
                // 设置默认值
                deviceName.text = "设备 (无法获取详情)"
                deviceManufacturer.text = "未知"
                deviceIpPort.text = "ID: 未知"
                deviceUdn.text = "类型: 未知"
            }
        }
        
        /**
         * 获取设备类型图标
         */
        private fun getDeviceTypeIcon(device: DLNACast.Device): String {
            return if (device.isTV) "📺" else "📱"
        }
        
        /**
         * 根据设备类型设置样式
         */
        private fun setDeviceTypeStyle(device: DLNACast.Device) {
            // 根据设备类型设置背景透明度（电视设备更明显）
            val alpha = if (device.isTV) 1.0f else 0.8f
            itemView.alpha = alpha
        }
    }
}
