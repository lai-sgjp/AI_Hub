package cn.aitavern.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.aitavern.core.*

@Composable fun ApiManager(vm: TavernViewModel, snapshot: Snapshot, room: ChatRoom?, close: ()->Unit, edit: (ApiProfile)->Unit) {
    val switching by vm.switchingApi.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<ApiProfile?>(null) }
    Editor("管理 API / 密钥",close,close,saveLabel="完成",showCancel=false) {
        Text("每个配置独立保存地址、模型和密钥。同一服务的不同密钥也可以分别命名添加。")
        if(room!=null) Text("切换仅影响当前房间。生成中切换会停止本轮并保存部分回复，不会自动重发。")
        Button(onClick={ edit(ApiProfile()) }) { Text("添加 API") }
        snapshot.profiles.forEach { profile ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(profile.name,style=MaterialTheme.typography.titleMedium)
                    Text(profile.model)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(room!=null) TextButton(onClick={ vm.switchApi(room.id,profile.id) },enabled=!switching && room.profileId!=profile.id) { Text(if(room.profileId==profile.id) "正在使用" else "切换到此 API") }
                        TextButton(onClick={ edit(profile) }) { Text("编辑") }
                        TextButton(onClick={ deleting=profile }) { Text("删除") }
                    }
                }
            }
        }
    }
    deleting?.let { profile -> AlertDialog(onDismissRequest={ deleting=null },title={ Text("删除 ${profile.name}？") },text={ Text("删除配置及其本机密钥。仍被房间使用的配置需先切换后才能删除。") },confirmButton={ TextButton(onClick={ vm.deleteApi(profile.id); deleting=null }) { Text("删除") } },dismissButton={ TextButton(onClick={ deleting=null }) { Text("取消") } }) }
}
