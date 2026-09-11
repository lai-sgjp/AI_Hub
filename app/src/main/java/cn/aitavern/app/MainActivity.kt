@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cn.aitavern.app

import android.os.Bundle
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.aitavern.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class MainActivity: ComponentActivity() {
    private lateinit var model: TavernViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model=ViewModelProvider(this)[TavernViewModel::class.java]
        setContent { TavernApp(model) }
    }
    override fun onStop() { super.onStop(); if(!isChangingConfigurations) model.stop() }
}

@Composable inline fun <reified T: Any> rememberModel(initial: T): MutableState<T> = rememberSaveable(stateSaver=Saver<T,String>(save={ TavernJson.encodeToString(it) },restore={ TavernJson.decodeFromString<T>(it) })) { mutableStateOf(initial) }

private val teal=Color(0xFF087F72)
@Composable fun TavernApp(vm: TavernViewModel = viewModel()) {
    val all by vm.snapshot.collectAsStateWithLifecycle()
    val activeWorld by vm.activeWorld.collectAsStateWithLifecycle()
    val world=all.worlds.find { it.id==activeWorld }
    val worldCharacters=all.characters.filter { it.id in world?.characterIds.orEmpty() }
    val s=all.copy(characters=worldCharacters,books=all.books.filter { it.id in (world?.bookIds.orEmpty()+worldCharacters.flatMap { c -> c.bookIds }) },rooms=all.rooms.filter { it.worldId==world?.id })
    val active by vm.activeRoom.collectAsStateWithLifecycle()
    val generating by vm.busy.collectAsStateWithLifecycle()
    val switching by vm.switchingApi.collectAsStateWithLifecycle()
    val busy=generating || switching
    val notice by vm.notice.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var character by remember { mutableStateOf<Character?>(null) }
    var book by remember { mutableStateOf<LoreBook?>(null) }
    var profile by remember { mutableStateOf<ApiProfile?>(null) }
    var apiManager by rememberSaveable { mutableStateOf(false) }
    var roomEditor by remember { mutableStateOf<ChatRoom?>(null) }
    var worldEditor by remember { mutableStateOf<World?>(null) }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.import(uri,tab==2) } }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::backup) }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::restore) }
    val dark=when(s.settings.theme) { "dark"->true; "light"->false; else->isSystemInDarkTheme() }
    MaterialTheme(colorScheme=if(dark) darkColorScheme(primary=Color(0xFF72DCC6),onPrimary=Color(0xFF00382F),primaryContainer=Color(0xFF155046),onPrimaryContainer=Color(0xFFB8F2DF),secondary=Color(0xFFBACAC3),background=Color(0xFF101715),surface=Color(0xFF101715),surfaceContainer=Color(0xFF1D2925),surfaceContainerHigh=Color(0xFF26332E),surfaceContainerLow=Color(0xFF16211C)) else lightColorScheme(primary=teal,onPrimary=Color.White,primaryContainer=Color(0xFFD2EFE5),onPrimaryContainer=Color(0xFF123B31),secondary=Color(0xFF51665F),background=Color(0xFFF6F8F7),surface=Color(0xFFF6F8F7),surfaceContainer=Color(0xFFEDF2EF),surfaceContainerHigh=Color(0xFFE5EDE8),surfaceContainerLow=Color(0xFFF1F5F2))) {
        val current=s.rooms.find { it.id==active }
        if(world==null) {
            Scaffold(topBar={ TopAppBar(title={ Text("选择你的世界",fontWeight=FontWeight.Bold) },actions={ TextButton(onClick={ apiManager=true }) { Text("管理 API") }; TextButton(onClick={ worldEditor=World() }) { Text("新建世界") } }) }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    item { SectionTitle("今晚，去哪里？","每个世界，都有自己的相遇和记忆。") }
                    if(all.worlds.isEmpty()) item { EmptyCard("创建第一个世界","设定背景，再邀请角色加入。世界之间的剧情记忆默认隔离。") }
                    items(all.worlds,key={ it.id }) { w ->
                        Card(onClick={ vm.activeWorld.value=w.id; vm.activeRoom.value=null },modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.Public,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(32.dp))
                                Text(w.name,style=MaterialTheme.typography.headlineSmall)
                                Text(w.description.ifBlank { "一个等待书写的世界" },maxLines=3,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(verticalAlignment=Alignment.CenterVertically) { Text("${w.characterIds.size} 位角色 · ${w.personas.size} 个人格",modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelMedium); TextButton(onClick={ worldEditor=w }) { Text("管理") } }
                            }
                        }
                    }
                    item { OutlinedButton(onClick={ restore.launch(arrayOf("application/zip","application/octet-stream")) }) { Text("从备份恢复世界") } }
                }
            }
        } else if(current!=null) {
            BackHandler { vm.activeRoom.value=null }
            ChatScreen(vm,s,current,onBack={ vm.activeRoom.value=null },onSettings={ roomEditor=current },onApi={ apiManager=true })
        } else Scaffold(
            topBar={ TopAppBar(title={ Column { Text(world.name,fontWeight=FontWeight.Bold); Text("AI 酒馆 · 让故事从一句话开始",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) } },navigationIcon={ IconButton(onClick={ vm.activeWorld.value=null }) { Icon(Icons.Default.Public,"切换世界") } },actions={ TextButton(onClick={ apiManager=true }) { Text("管理 API") }; IconButton(onClick={ worldEditor=world }) { Icon(Icons.Default.Edit,"管理世界") } }) },
            bottomBar={ NavigationBar {
                val names=listOf("聊天","角色","世界书","设置")
                val icons=listOf(Icons.AutoMirrored.Filled.Chat,Icons.Default.People,Icons.Default.MenuBook,Icons.Default.Settings)
                names.forEachIndexed { i,name -> NavigationBarItem(selected=tab==i,onClick={ tab=i },icon={ Icon(icons[i],name) },label={ Text(name) }) }
            } }
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                when(tab) {
                    0 -> {
                        item { SectionTitle("你的故事","${s.rooms.size} 个聊天 · 记录保存在手机") }
                        item { Button(onClick={ if(s.characters.isEmpty() || s.profiles.isEmpty()) vm.report("先创建角色并在设置中添加 API，再开启故事。") else roomEditor=ChatRoom(worldId=world.id,memberIds=listOf(s.characters.first().id),profileId=s.profiles.first().id,userName=world.personas.firstOrNull()?.name ?: "我",persona=world.personas.firstOrNull()?.description.orEmpty()) },modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Add,null); Text("新建聊天") } }
                        if(s.rooms.isEmpty()) item { EmptyCard("还没有开始的故事","先去角色页创建或导入角色，再添加 API。") }
                        items(s.rooms,key={ it.id }) { room ->
                            Card(onClick={ vm.activeRoom.value=room.id },modifier=Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Text(room.name,style=MaterialTheme.typography.titleMedium)
                                    Text(room.memberIds.mapNotNull { id -> s.characters.find { it.id==id }?.name }.joinToString(" · "),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                                    Text(s.messages.lastOrNull { it.roomId==room.id }?.text?.take(90) ?: "故事正等待你的第一句话",maxLines=2,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    1 -> {
                        item { SectionTitle("角色","创建人设，或带上熟悉的角色卡") }
                        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(onClick={ character=Character() }) { Text("创建角色") }; OutlinedButton(onClick={ importer.launch(arrayOf("application/json","image/png","application/octet-stream")) }) { Text("导入角色卡") } } }
                        if(s.characters.isEmpty()) item { EmptyCard("认识一个新角色","支持 V1/V2 PNG、JSON 角色卡") }
                        items(s.characters,key={ it.id }) { c -> Card(onClick={ character=c },modifier=Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) { Avatar(c); Column { Text(c.name,style=MaterialTheme.typography.titleMedium); Text(c.description.ifBlank { "点击编辑人设和开场白" }.take(100),maxLines=2,style=MaterialTheme.typography.bodyMedium) } } } }
                    }
                    2 -> {
                        item { SectionTitle("世界书","让地点、人物与规则随剧情出现") }
                        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(onClick={ book=LoreBook() }) { Text("创建世界书") }; OutlinedButton(onClick={ importer.launch(arrayOf("application/json","application/octet-stream")) }) { Text("导入世界书") } } }
                        items(s.books,key={ it.id }) { b -> Card(onClick={ book=b },modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text(b.name,style=MaterialTheme.typography.titleMedium); Text("${b.entries.size} 条设定 · ${b.entries.count { it.enabled }} 条启用",style=MaterialTheme.typography.bodyMedium) } } }
                    }
                    3 -> {
                        item { SectionTitle("连接与偏好","API 密钥仅在本机加密保存") }
                        item { Button(onClick={ apiManager=true }) { Text("管理 API / 密钥") }; Text("版本 ${BuildConfig.VERSION_NAME} · 下载新版 APK 后点开即可覆盖更新，请勿卸载旧版。") }
                        items(s.profiles,key={ it.id }) { p -> Card(onClick={ profile=p },modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(p.name,style=MaterialTheme.typography.titleMedium); Text(p.model,style=MaterialTheme.typography.bodyMedium) } } }
                        item { Text("外观",style=MaterialTheme.typography.titleMedium); FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach { (value,label) -> FilterChip(selected=s.settings.theme==value,onClick={ vm.theme(value) },label={ Text(label) }) } } }
                        item { HorizontalDivider(); Text("备份",Modifier.padding(top=16.dp),style=MaterialTheme.typography.titleMedium); Text("含角色、聊天与设定，不含密钥。恢复会新增副本。",style=MaterialTheme.typography.bodySmall); Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick={ export.launch("AI酒馆备份.zip") }) { Text("导出备份") }; OutlinedButton(onClick={ restore.launch(arrayOf("application/zip","application/octet-stream")) },enabled=!busy) { Text("恢复备份") } } }
                        item { Text("聊天上下文会发送至你配置的 API。自动摘要也会消耗 API 额度。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        item { TextButton(onClick=vm::bundleReport) { Text("内置资料导入报告") } }
                    }
                }
            }
        }
        character?.let { value -> CharacterEditor(value,s.books,{ character=null }) { vm.save(it); character=null } }
        book?.let { value -> BookEditor(value,{ book=null }) { vm.save(it); book=null } }
        if(apiManager) ApiManager(vm,all,current,{ apiManager=false },{ profile=it })
        profile?.let { value -> ProfileEditor(value,vm,{ profile=null }) { p,key -> vm.save(p,key); profile=null } }
        roomEditor?.let { value -> RoomEditor(value,s,{ roomEditor=null }) { vm.save(it); roomEditor=null } }
        worldEditor?.let { value -> WorldEditor(value,all,{ worldEditor=null }) { vm.save(it); worldEditor=null } }
        notice?.let { text -> AlertDialog(onDismissRequest={ vm.notice.value=null },title={ Text("提示") },text={ Text(text,Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) },confirmButton={ TextButton(onClick={ vm.notice.value=null }) { Text("知道了") } }) }
    }
}

@Composable fun SectionTitle(title: String, subtitle: String) { Column(verticalArrangement=Arrangement.spacedBy(6.dp)) { Text(title,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold); Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable fun EmptyCard(title: String, subtitle: String) { OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(28.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) { Text(title,style=MaterialTheme.typography.titleMedium); Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable fun Avatar(character: Character?) {
    val bitmap by produceState<android.graphics.Bitmap?>(null,character?.avatar) {
        value=withContext(Dispatchers.Default) { runCatching {
            val bytes=java.util.Base64.getDecoder().decode(character?.avatar.orEmpty())
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=(maxOf(bounds.outWidth,bounds.outHeight)/128).coerceAtLeast(1) })
        }.getOrNull() }
    }
    Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer,modifier=Modifier.size(40.dp)) {
        val image=bitmap
        if(image!=null) Image(image.asImageBitmap(),character?.name,contentScale=ContentScale.Crop)
        else Box(contentAlignment=Alignment.Center) { Text(character?.name?.take(1) ?: "我",fontWeight=FontWeight.Bold) }
    }
}

@Composable fun ChatScreen(vm: TavernViewModel, s: Snapshot, room: ChatRoom, onBack: ()->Unit,onSettings: ()->Unit,onApi: ()->Unit) {
    val generating by vm.busy.collectAsStateWithLifecycle()
    val switching by vm.switchingApi.collectAsStateWithLifecycle()
    val busy=generating || switching
    val live by vm.live.collectAsStateWithLifecycle()
    var text by rememberSaveable(room.id) { mutableStateOf("") }
    var nominated by rememberSaveable(room.id) { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var memoryOpen by rememberSaveable { mutableStateOf(false) }
    var editText by rememberSaveable { mutableStateOf("") }
    val clipboard=LocalClipboardManager.current
    val messages=s.messages.filter { it.roomId==room.id }.toMutableList().apply { live?.takeIf { it.roomId==room.id }?.let { value -> val i=indexOfFirst { it.id==value.id }; if(i>=0) this[i]=value else add(value) } }.sortedBy { it.sequence }
    val members=room.memberIds.mapNotNull { id -> s.characters.find { it.id==id } }
    val scroll=rememberLazyListState()
    LaunchedEffect(room.id,messages.size,messages.lastOrNull()?.text?.length) { if(messages.isNotEmpty() && (!scroll.canScrollForward || scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >= messages.size-2)) scroll.scrollToItem(messages.lastIndex) }
    Scaffold(
        topBar={ TopAppBar(title={ Column { Text(room.name,maxLines=1); Text(if(busy) "正在组织下一句话…" else "${members.size} 位角色 · 本轮 ${room.replies} 位回复",style=MaterialTheme.typography.labelSmall) } },navigationIcon={ IconButton(onClick=onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回") } },actions={ IconButton(onClick={ memoryOpen=true }) { Icon(Icons.Default.Psychology,"记忆中心") }; IconButton(onClick=onSettings,enabled=!busy) { Icon(Icons.Default.Tune,"房间设置") } }) },
        bottomBar={ Surface(tonalElevation=2.dp) { Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal=12.dp,vertical=8.dp)) {
            TextButton(onClick=onApi) { Text("API：${s.profiles.find { it.id==room.profileId }?.name ?: "未配置"} · 切换 / 管理") }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                FilterChip(selected=nominated==null,onClick={ nominated=null },label={ Text("自动选人") },enabled=!busy)
                members.forEach { c -> FilterChip(selected=nominated==c.id,onClick={ nominated=c.id },label={ Text("@${c.name}") },enabled=!busy) }
            }
            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value=text,onValueChange={ text=it },placeholder={ Text("写下你的回应…") },modifier=Modifier.weight(1f),maxLines=5,shape=RoundedCornerShape(22.dp))
                if(busy) FilledIconButton(onClick=vm::stop) { Icon(Icons.Default.Stop,"停止") }
                else FilledIconButton(onClick={ vm.send(text,nominated); text="" }) { Icon(if(text.isBlank()) Icons.Default.PlayArrow else Icons.Default.Send,if(text.isBlank()) "继续" else "发送") }
            }
        } } }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),state=scroll,contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            if(messages.isEmpty()) item {
                EmptyCard("${room.name}，即将开场",room.scenario.ifBlank { "发出第一句话，或选择角色的开场白。" })
                members.forEach { c -> (listOf(c.greeting)+c.alternateGreetings).filter { it.isNotBlank() }.forEach { greeting -> TextButton(onClick={ vm.greeting(room,c,greeting) }) { Text("${c.name}：${greeting.take(70)}",maxLines=2) } } }
            }
            items(messages,key={ it.id }) { msg ->
                val c=members.find { it.id==msg.speakerId }
                Column(Modifier.fillMaxWidth(),horizontalAlignment=if(msg.speakerId==null) Alignment.End else Alignment.Start) {
                    Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(msg.speakerId!=null) Avatar(c)
                        Surface(shape=RoundedCornerShape(16.dp),color=if(msg.speakerId==null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.widthIn(max=310.dp)) {
                            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(if(msg.speakerId==null) room.userName else c?.name ?: "角色",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                                SelectionContainer { Text(msg.text.ifBlank { "…" },style=MaterialTheme.typography.bodyLarge) }
                                if(msg.status!="complete") Text(when(msg.status) { "generating"->"正在输入…"; "failed"->"生成失败 · 可重新生成"; else->"已中断 · 可重新生成" },style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Row {
                        TextButton(onClick={ clipboard.setText(AnnotatedString(msg.text)) },contentPadding=PaddingValues(horizontal=6.dp)) { Text("复制",style=MaterialTheme.typography.labelSmall) }
                        TextButton(onClick={ editing=msg; editText=msg.text },enabled=!busy,contentPadding=PaddingValues(horizontal=6.dp)) { Text("编辑分支",style=MaterialTheme.typography.labelSmall) }
                        if(msg.speakerId!=null) TextButton(onClick={ vm.branch(msg,null) },enabled=!busy,contentPadding=PaddingValues(horizontal=6.dp)) { Text("重新生成",style=MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
    editing?.let { msg -> AlertDialog(onDismissRequest={ editing=null },title={ Text("编辑并建立新分支") },text={ Column { Text("原剧情保留，新分支从这里继续。"); Field("消息",editText,{ editText=it },minLines=4) } },confirmButton={ TextButton(onClick={ vm.branch(msg,editText); editing=null },enabled=editText.isNotBlank()) { Text("保存分支") } },dismissButton={ TextButton(onClick={ editing=null }) { Text("取消") } }) }
    if(memoryOpen) MemoryPanel(vm,s,room,{ memoryOpen=false })
}

@Composable fun Field(label: String,value: String,onChange: (String)->Unit,minLines: Int=1,secret: Boolean=false) { OutlinedTextField(value=value,onValueChange=onChange,label={ Text(label) },modifier=Modifier.fillMaxWidth(),minLines=minLines,maxLines=if(secret) 1 else maxOf(minLines,8),singleLine=secret,visualTransformation=if(secret) PasswordVisualTransformation() else VisualTransformation.None) }
@Composable fun Editor(title: String,onClose: ()->Unit,onSave: ()->Unit,valid: Boolean=true,saveLabel: String="保存",showCancel: Boolean=true,content: @Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth().padding(12.dp).fillMaxHeight(0.93f)) {
            Column(Modifier.imePadding().padding(16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) { Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f)); if(showCancel) TextButton(onClick=onClose) { Text("取消") }; TextButton(onClick=onSave,enabled=valid) { Text(saveLabel) } }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
            }
        }
    }
}
@Composable fun CharacterEditor(initial: Character,books: List<LoreBook>,close: ()->Unit,save: (Character)->Unit) {
    var c by rememberModel(initial)
    var galleryOpen by rememberSaveable { mutableStateOf(false) }
    Editor("角色设定",close,{ save(c) },c.name.isNotBlank()) {
        Field("名字",c.name,{ c=c.copy(name=it) }); Field("角色描述",c.description,{ c=c.copy(description=it) },3)
        Field("性格",c.personality,{ c=c.copy(personality=it) },2); Field("角色场景",c.scenario,{ c=c.copy(scenario=it) },2)
        Field("开场白",c.greeting,{ c=c.copy(greeting=it) },3)
        Field("备选开场白（用单独一行 --- 分隔）",c.alternateGreetings.joinToString("\n---\n"),{ c=c.copy(alternateGreetings=it.split("\n---\n")) },3)
        Field("对话示例",c.examples,{ c=c.copy(examples=it) },2); Field("角色指令",c.systemPrompt,{ c=c.copy(systemPrompt=it) },2); Field("末尾指令",c.postHistory,{ c=c.copy(postHistory=it) },2)
        Text("关联世界书"); ChoiceChips(books.map { it.id to it.name },c.bookIds) { c=c.copy(bookIds=it) }
        if(c.gallery.isNotEmpty()) OutlinedButton(onClick={ galleryOpen=true }) { Text("查看角色立绘（${c.gallery.size}）") }
    }
    if(galleryOpen) CharacterGalleryDialog(c) { galleryOpen=false }
}

@Composable fun CharacterGalleryDialog(character: Character,close: ()->Unit) {
    var index by rememberSaveable(character.id) { mutableIntStateOf(0) }
    val context=LocalContext.current
    val image=character.gallery.getOrNull(index)
    val bitmap by produceState<android.graphics.Bitmap?>(null,image?.assetPath) {
        value=withContext(Dispatchers.IO) {
            runCatching {
                val path=requireNotNull(image).assetPath
                require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path && path.split('/').none { it.isBlank() || it=="." || it==".." })
                context.assets.open("bundled/$path").use(BitmapFactory::decodeStream)
            }.getOrNull()
        }
    }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth().padding(12.dp).fillMaxHeight(0.9f)) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("${character.name} · 角色立绘",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                    TextButton(onClick=close) { Text("关闭") }
                }
                if(image==null) Text("没有可查看的立绘。")
                else {
                    Text(image.title,style=MaterialTheme.typography.titleMedium)
                    if(bitmap!=null) Image(bitmap!!.asImageBitmap(),contentDescription=image.title,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxWidth().weight(1f))
                    else Text("立绘无法读取：${image.assetPath}",color=MaterialTheme.colorScheme.error)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        OutlinedButton(onClick={ index=(index-1+character.gallery.size)%character.gallery.size },enabled=character.gallery.size>1) { Text("上一张") }
                        Text("${index+1} / ${character.gallery.size}",modifier=Modifier.align(Alignment.CenterVertically))
                        OutlinedButton(onClick={ index=(index+1)%character.gallery.size },enabled=character.gallery.size>1) { Text("下一张") }
                    }
                }
            }
        }
    }
}
@Composable fun ChoiceChips(options: List<Pair<String,String>>,selected: List<String>,change: (List<String>)->Unit) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { options.forEach { (id,label) -> FilterChip(selected=id in selected,onClick={ change(if(id in selected) selected-id else selected+id) },label={ Text(label) }) } }
}
@Composable fun RoomEditor(initial: ChatRoom,s: Snapshot,close: ()->Unit,save: (ChatRoom)->Unit) {
    var r by rememberModel(initial)
    Editor("房间与记忆",close,{ save(r) },r.name.isNotBlank() && r.memberIds.size in 1..8 && r.profileId.isNotBlank()) {
        Field("房间名字",r.name,{ r=r.copy(name=it) }); Text("参与角色（1～8 位）")
        ChoiceChips(s.characters.map { it.id to it.name },r.memberIds) { if(it.size<=8) r=r.copy(memberIds=it) }
        Text("本轮回复人数"); Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { (1..2).forEach { n -> FilterChip(selected=r.replies==n,onClick={ r=r.copy(replies=n) },label={ Text("$n 位") }) } }
        Text("房间 API"); FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { s.profiles.forEach { p -> FilterChip(selected=r.profileId==p.id,onClick={ r=r.copy(profileId=p.id) },label={ Text(p.name) }) } }
        val personas=s.worlds.find { it.id==r.worldId }?.personas.orEmpty()
        if(personas.isNotEmpty()) { Text("选择你扮演的人格"); FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { personas.forEach { persona -> FilterChip(selected=r.userName==persona.name && r.persona==persona.description,onClick={ r=r.copy(userName=persona.name,persona=persona.description) },label={ Text(persona.name) }) } } }
        Field("你的名字",r.userName,{ r=r.copy(userName=it) }); Field("你的人设",r.persona,{ r=r.copy(persona=it) },2); Field("剧情场景",r.scenario,{ r=r.copy(scenario=it) },3)
        Field("置顶记忆（始终发送）",r.pinned,{ r=r.copy(pinned=it) },3); Field("小记忆 · 近期阶段摘要",r.summary,{ r=r.copy(summary=it) },4)
        Field("大记忆 · 长期剧情摘要",r.longSummary,{ r=r.copy(longSummary=it) },4)
        Row(verticalAlignment=Alignment.CenterVertically) { Text("定期整理记忆（每 12 条完整消息）",Modifier.weight(1f)); Switch(r.autoMemory,{ r=r.copy(autoMemory=it) }) }
        Text("已摘要至消息序号 ${r.summaryThrough+1}；原聊天记录仍保留。",style=MaterialTheme.typography.bodySmall)
        Text("房间世界书"); ChoiceChips(s.books.map { it.id to it.name },r.bookIds) { r=r.copy(bookIds=it) }
    }
}
@Composable fun ProfileEditor(initial: ApiProfile,vm: TavernViewModel,close: ()->Unit,save: (ApiProfile,String?)->Unit) {
    var p by rememberModel(initial)
    var key by remember { mutableStateOf("") }
    var keyChanged by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var context by rememberSaveable { mutableStateOf(p.contextSize.toString()) }; var output by rememberSaveable { mutableStateOf(p.maxOutput.toString()) }
    Editor("API 配置",close,{ save(p.copy(contextSize=context.toInt(),maxOutput=output.toInt()),if(keyChanged) key else null) },p.name.isNotBlank() && p.model.isNotBlank() && (context.toIntOrNull() ?: 0)>(output.toIntOrNull() ?: 0) && (output.toIntOrNull() ?: 0)>0) {
        Field("配置名字",p.name,{ p=p.copy(name=it) }); Field("基础地址（含 /v1，不含 /chat/completions）",p.baseUrl,{ p=p.copy(baseUrl=it) })
        Field("API 密钥（留空保留已保存密钥）",key,{ key=it; keyChanged=true },secret=true)
        Field("模型",p.model,{ p=p.copy(model=it) })
        Field("Embedding 模型（可选，用于记忆向量化）",p.embeddingModel,{ p=p.copy(embeddingModel=it) })
        Text("Embedding 使用同一基础地址下的 /embeddings。留空时使用关键词检索，可在记忆中心重建索引。",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick={ vm.models(p,if(keyChanged) key else null) { models=it } }) { Text("获取模型") }; OutlinedButton(onClick={ vm.test(p,if(keyChanged) key else null) },enabled=p.model.isNotBlank()) { Text("测试连接") } }
        if(models.isNotEmpty()) FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { models.forEach { id -> FilterChip(selected=p.model==id,onClick={ p=p.copy(model=id) },label={ Text(id) }) } }
        Field("上下文长度",context,{ context=it }); Field("最大输出",output,{ output=it })
        Text("长角色卡默认预留 32K 上下文，请按服务实际支持的长度设置。",style=MaterialTheme.typography.bodySmall)
        Text("温度 ${"%.1f".format(p.temperature)}"); Slider(value=p.temperature.toFloat(),onValueChange={ p=p.copy(temperature=it.toDouble()) },valueRange=0f..2f)
        Row(verticalAlignment=Alignment.CenterVertically) { Text("流式显示",Modifier.weight(1f)); Switch(checked=p.stream,onCheckedChange={ p=p.copy(stream=it) }) }
        Text("上下文为保守估算。连接测试会发送一条小请求。",style=MaterialTheme.typography.bodySmall)
    }
}
@Composable fun BookEditor(initial: LoreBook,close: ()->Unit,save: (LoreBook)->Unit) {
    var b by rememberModel(initial)
    var entry by remember { mutableStateOf<LoreEntry?>(null) }
    Editor("世界书",close,{ save(b) },b.name.isNotBlank()) {
        Field("世界书名字",b.name,{ b=b.copy(name=it) }); Text("扫描最近 6 条消息，优先级越大越靠前。",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={ entry=LoreEntry() }) { Text("添加条目") }
        b.entries.forEach { e -> Card(onClick={ entry=e },modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(e.content.take(100).ifBlank { "空条目" }); Text("${if(e.enabled) "启用" else "禁用"} · ${if(e.constant) "常驻" else e.keys.joinToString()} · 优先级 ${e.priority}",style=MaterialTheme.typography.labelSmall) } } }
    }
    entry?.let { initialEntry ->
        var e by rememberModel(initialEntry)
        Editor("世界书条目",{ entry=null },{ b=b.copy(entries=b.entries.filterNot { it.id==e.id }+e); entry=null }) {
            Field("关键词（逗号分隔）",e.keys.joinToString(","),{ e=e.copy(keys=it.split(',', '，').map(String::trim)) })
            Field("设定内容",e.content,{ e=e.copy(content=it) },5)
            Field("优先级",e.priority.toString(),{ it.toIntOrNull()?.let { n -> e=e.copy(priority=n) } })
            Row(verticalAlignment=Alignment.CenterVertically) { Text("启用",Modifier.weight(1f)); Switch(e.enabled,{ e=e.copy(enabled=it) }) }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("常驻",Modifier.weight(1f)); Switch(e.constant,{ e=e.copy(constant=it) }) }
            TextButton(onClick={ b=b.copy(entries=b.entries.filterNot { it.id==e.id }); entry=null }) { Text("删除条目") }
        }
    }
}
