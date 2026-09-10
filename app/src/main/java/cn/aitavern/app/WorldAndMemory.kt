@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cn.aitavern.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.aitavern.core.*

@Composable fun WorldEditor(initial: World,s: Snapshot,close: ()->Unit,save: (World)->Unit) {
    var w by rememberModel(initial)
    var persona by remember { mutableStateOf<Persona?>(null) }
    var document by remember { mutableStateOf<WorldDocument?>(null) }
    Editor("管理世界",close,{ save(w) },w.name.isNotBlank()) {
        Field("世界名字",w.name,{ w=w.copy(name=it) }); Field("世界观与背景",w.description,{ w=w.copy(description=it) },5)
        Text("世界背景会用于此世界的所有聊天。剧情记忆默认隔离。",style=MaterialTheme.typography.bodySmall)
        Text("你可扮演的人格",style=MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick={ persona=Persona() }) { Text("添加人格") }
        w.personas.forEach { p -> Card(onClick={ persona=p },modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(p.name); Text(p.description.take(100),style=MaterialTheme.typography.bodySmall) } } }
        Text("加入此世界的角色",style=MaterialTheme.typography.titleMedium)
        ChoiceChips(s.characters.map { it.id to it.name },w.characterIds) { ids ->
            val required=s.rooms.filter { it.worldId==w.id }.flatMap { it.memberIds }.toSet()
            w=w.copy(characterIds=(ids+required).distinct())
        }
        Text("已被本世界房间使用的角色会保留。进入世界后可创建或导入新角色。",style=MaterialTheme.typography.bodySmall)
        Text("全世界共用的世界书"); ChoiceChips(s.books.map { it.id to it.name },w.bookIds) { w=w.copy(bookIds=it) }
        if(w.documents.isNotEmpty()) { Text("随附参考资料",style=MaterialTheme.typography.titleMedium); w.documents.forEach { doc -> TextButton(onClick={ document=doc }) { Text(doc.title) } } }
    }
    persona?.let { initialPersona ->
        var p by rememberModel(initialPersona)
        Editor("用户人格",{ persona=null },{ w=w.copy(personas=w.personas.filterNot { it.id==p.id }+p); persona=null },p.name.isNotBlank()) {
            Field("人格名字",p.name,{ p=p.copy(name=it) }); Field("身份、性格与背景",p.description,{ p=p.copy(description=it) },6)
            TextButton(onClick={ w=w.copy(personas=w.personas.filterNot { it.id==p.id }); persona=null }) { Text("删除人格") }
        }
    }
    document?.let { doc -> Editor(doc.title,{ document=null },{ document=null },saveLabel="完成",showCancel=false) { Text(doc.body) } }
}

@Composable fun MemoryPanel(vm: TavernViewModel,s: Snapshot,room: ChatRoom,close: ()->Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryFact?>(null) }
    val facts=s.memories.filter { it.worldId==room.worldId && (it.roomId==room.id || it.scope=="world") }
    val model=s.profiles.find { it.id==room.profileId }
    val fingerprint=model?.let { it.baseUrl.trimEnd('/')+"|"+it.embeddingModel }.orEmpty()
    Editor("记忆中心",close,close,saveLabel="完成",showCancel=false) {
        Text("原始对话 → 小记忆 → 大记忆 → 记忆表格 → 相关记忆检索",style=MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("总览","记忆表","阶段记录").forEachIndexed { i,label -> FilterChip(selected=tab==i,onClick={ tab=i },label={ Text(label) }) } }
        when(tab) {
            0 -> {
                Text("记忆索引",style=MaterialTheme.typography.titleMedium)
                Text("${facts.count { it.enabled }} 条启用 · ${facts.count { it.embedding.isNotEmpty() && it.embeddingModel==fingerprint }} 条有效向量")
                Text(if(model?.embeddingModel.isNullOrBlank()) "当前使用关键词检索。可在 API 配置填写 embedding 模型。" else "向量模型：${model?.embeddingModel}。接口失败时回退关键词检索。",style=MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={ vm.organizeMemory(room.id) },enabled=!busy) { Text("整理记忆") }
                    OutlinedButton(onClick={ vm.organizeMemory(room.id,true) },enabled=!busy) { Text("重建向量索引") }
                    if(busy) TextButton(onClick=vm::stop) { Text("停止") }
                }
                Text("手动整理提取最近 12 条完整消息；定期整理会更新大小摘要与记忆表。操作会调用 API。",style=MaterialTheme.typography.bodySmall)
                HorizontalDivider(); Text("置顶记忆",style=MaterialTheme.typography.titleMedium); Text(room.pinned.ifBlank { "暂无，可在房间设置编辑。" })
                Text("小记忆 · 当前阶段",style=MaterialTheme.typography.titleMedium); Text(room.summary.ifBlank { "剧情积累后自动整理。" })
                Text("大记忆 · 长期剧情",style=MaterialTheme.typography.titleMedium); Text(room.longSummary.ifBlank { "暂无长期摘要。" })
                Text("已覆盖至消息 ${room.summaryThrough+1}，更早的阶段摘要在“阶段记录”中保留。",style=MaterialTheme.typography.bodySmall)
            }
            1 -> {
                OutlinedButton(onClick={ editing=MemoryFact(worldId=room.worldId,roomId=room.id,locked=true) },enabled=!busy) { Text("添加记忆") }
                Field("搜索记忆表",query,{ query=it })
                // Horizontally scrollable table; rows remain editable as complete records.
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Row(Modifier.background(MaterialTheme.colorScheme.primaryContainer).padding(10.dp)) {
                        Text("类型",Modifier.width(64.dp)); Text("对象",Modifier.width(100.dp)); Text("记忆事实",Modifier.width(230.dp)); Text("来源 / 状态",Modifier.width(150.dp))
                    }
                    facts.filter { query.isBlank() || (it.subject+it.content+it.category).contains(query,true) }.forEach { f ->
                        Row(Modifier.clickable(enabled=!busy) { editing=f }.padding(10.dp)) {
                            Text(f.category,Modifier.width(64.dp)); Text(f.subject,Modifier.width(100.dp)); Text(f.content,Modifier.width(230.dp))
                            Text("${if(f.sourceStart<0) "手动" else "消息 ${f.sourceStart+1}～${f.sourceEnd+1}"}\n${if(f.locked) "已锁定" else "自动更新"} · ${if(f.enabled) "启用" else "禁用"}\n${if(f.scope=="world") "世界共享" else "当前剧情"}",Modifier.width(150.dp),style=MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
                Text("左右滑动查看来源和状态；点击条目编辑。锁定后，自动整理不会覆盖该事实。",style=MaterialTheme.typography.bodySmall)
            }
            2 -> {
                val segments=s.segments.filter { it.roomId==room.id }.sortedBy { it.start }
                if(segments.isEmpty()) Text("暂无阶段摘要。原始聊天记录始终保留。")
                segments.forEach { segment -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text("消息 ${segment.start+1}～${segment.end+1}",style=MaterialTheme.typography.labelLarge); Text(segment.text) } } }
            }
        }
    }
    editing?.let { initial ->
        var f by rememberModel(initial)
        Editor("记忆条目",{ editing=null },{ vm.save(f); editing=null },f.subject.isNotBlank() && f.content.isNotBlank()) {
            Field("类型",f.category,{ f=f.copy(category=it) }); Field("对象（建议包含具体属性）",f.subject,{ f=f.copy(subject=it) }); Field("事实内容",f.content,{ f=f.copy(content=it) },5)
            Row(verticalAlignment=Alignment.CenterVertically) { Text("锁定，避免自动覆盖",Modifier.weight(1f)); Switch(f.locked,{ f=f.copy(locked=it) }) }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("启用检索",Modifier.weight(1f)); Switch(f.enabled,{ f=f.copy(enabled=it) }) }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("在此世界所有剧情共享",Modifier.weight(1f)); Switch(f.scope=="world",{ f=f.copy(scope=if(it) "world" else "room") }) }
            Text("世界共享会使其他房间和分支也能召回此事实。默认仅当前剧情。",style=MaterialTheme.typography.bodySmall)
        }
    }
}
