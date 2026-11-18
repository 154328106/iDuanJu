package app.iduanju.novel

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.iduanju.core.novel.NovelMedia
import app.iduanju.core.novel.LegadoMedia
import app.iduanju.core.util.NetGuard
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.UnknownHostException
import java.net.Socket
import java.net.SocketAddress
import java.net.InetSocketAddress
import java.net.InetAddress
import java.io.IOException
import javax.net.SocketFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Defense before TCP connect (network interceptors run after connect, and IP literals may skip Dns). */
internal class GuardedMediaSocketFactory:SocketFactory(){
 override fun createSocket():Socket=object:Socket(){
  override fun connect(endpoint:SocketAddress?,timeout:Int){
   val target=endpoint as? InetSocketAddress
   if(target?.address==null || target.port !in listOf(80,443) || NetGuard.isBlockedIP(target.address.hostAddress.orEmpty()))throw IOException("媒体连接拒绝内网地址或非标准端口")
   super.connect(endpoint,timeout)
  }
 }
 override fun createSocket(host:String,port:Int):Socket=createSocket().apply{connect(InetSocketAddress(host,port))}
 override fun createSocket(host:String,port:Int,local:InetAddress,localPort:Int):Socket=createSocket().apply{bind(InetSocketAddress(local,localPort));connect(InetSocketAddress(host,port))}
 override fun createSocket(host:InetAddress,port:Int):Socket=createSocket().apply{connect(InetSocketAddress(host,port))}
 override fun createSocket(host:InetAddress,port:Int,local:InetAddress,localPort:Int):Socket=createSocket().apply{bind(InetSocketAddress(local,localPort));connect(InetSocketAddress(host,port))}
}

/** Validate each request, not just DNS lookup: OkHttp may bypass Dns for IP-literal redirects. */
internal fun guardedMediaRequest(req:okhttp3.Request,origin:String):okhttp3.Request {
 LegadoMedia.checkedURL(req.url.toString())
 return if(LegadoMedia.sameOrigin(req.url.toString(),origin))req else req.newBuilder().removeHeader("Authorization").removeHeader("Cookie").build()
}

/** Native Media3 consumer, separate from text cache/TTS. No untrusted WebView or DRM helper. */
@Composable
fun NovelMediaPlayer(media:NovelMedia,title:String,onClose:()->Unit,onPrevious:(()->Unit)?,onNext:(()->Unit)?) {
 val context=LocalContext.current
 var failure by remember(media){mutableStateOf<String?>(null)}
 var progress by remember(media){mutableStateOf("正在缓冲") }
 val player=remember(media){
  fun strip(req:okhttp3.Request)=guardedMediaRequest(req,media.url)
  val client=OkHttpClient.Builder().socketFactory(GuardedMediaSocketFactory()).dns(object:Dns { override fun lookup(hostname:String):List<java.net.InetAddress> =
   Dns.SYSTEM.lookup(hostname).also { addresses -> if(addresses.any{NetGuard.isBlockedIP(it.hostAddress.orEmpty())})throw UnknownHostException("媒体请求拒绝内网地址") }
  }).addInterceptor { chain->chain.proceed(strip(chain.request())) }
   .addNetworkInterceptor { chain->chain.proceed(strip(chain.request())) }.build()
  val factory=OkHttpDataSource.Factory(client).setDefaultRequestProperties(media.headers)
  ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply {
   addListener(object:Player.Listener{override fun onPlayerError(error:PlaybackException){failure=error.errorCodeName+": "+error.message}})
   setMediaItem(MediaItem.fromUri(media.url));prepare();playWhenReady=true
  }
 }
 LaunchedEffect(player){while(isActive){progress="已播放 %.2f 秒 · %s".format(java.util.Locale.ROOT,player.currentPosition/1000.0,if(player.playbackState==Player.STATE_ENDED)"结束"else if(player.isPlaying)"播放中"else "缓冲/暂停");delay(200)}}
 DisposableEffect(player){onDispose{player.release()}}
 BackHandler{onClose()}
 Column(Modifier.fillMaxSize().testTag("novel.media.player")){
  Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(title,Modifier.weight(1f));TextButton(onClick=onClose){Text("关闭播放")}}
  if(media.sourceType==1)Text("有声书 · 原生音频播放",Modifier.padding(12.dp))
  AndroidView(factory={ctx->PlayerView(ctx).apply{this.player=player;useController=true;controllerShowTimeoutMs=0;layoutParams=ViewGroup.LayoutParams(-1,-1)}},update={it.player=player},modifier=Modifier.fillMaxWidth().weight(1f))
  Text(progress,Modifier.padding(12.dp).testTag("novel.media.status"))
  failure?.let{Text(it,Modifier.padding(12.dp).testTag("novel.media.error"),color=MaterialTheme.colorScheme.error)}
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){TextButton(onClick={onPrevious?.invoke()},enabled=onPrevious!=null){Text("上一章")};TextButton(onClick={onNext?.invoke()},enabled=onNext!=null){Text("下一章")}}
 }
}
