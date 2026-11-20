package app.iduanju.core.hongguo

import app.iduanju.core.util.J
import app.iduanju.core.util.GoURL
import app.iduanju.core.util.ProviderException
import org.json.JSONObject

data class HGRankedActor(val id: String, val name: String, val avatar: String, val rank: Int,
    val score: String, val intro: String, val works: List<String>, val change: Int, val changeNum: Int) {
    val gender = ActorGender.infer(name, intro)
}
data class HGActorRankPage(val actors: List<HGRankedActor>, val date: String, val hasMore: Boolean, val nextOffset: Int, val sessionId: String)

enum class ActorGender(val label: String) {
    All("全部"), Male("男演员"), Female("女演员");

    companion object {
        private val femaleNames = setOf("王志欣", "王小亿", "何雅馨", "至春禾", "张晋宜", "余茵", "郭宇欣", "董薇", "丁磊", "郇依心",
        "孟娜", "林秋奈", "羽翎", "马乐婕", "马秋元", "谭盐盐", "韩雨彤", "黄宁馨", "高子怡", "梁雯晶",
        "袁祎晴", "黎晟萱", "刘念", "袁子晶", "翟一莹", "贾翼瑄", "钟熙", "斯佳丽", "柴慧琴", "滕泽文",
        "孙芊芊", "许梦圆", "赵夕汐", "徐艺真", "赵佳", "张楚萱", "侯呈玥", "黄云云", "刘美娜", "邓灵枢",
        "孙艺燃", "张嘉妤", "陈淑云", "李偌菡", "琪梵", "索菲", "天儿", "姜熙饶", "何雨桐", "方梨",
        "苏曼", "董洁", "涂蕊", "杨泽琳", "冯艺然", "周洛伊", "裴颤", "李柯以", "林琦婷", "蒋昕妍",
        "赵慧楠", "朱诗妤", "胡冬晴")
        private val maleNames = setOf("申浩男", "柯淳", "鹿单东", "白方文", "岳冬峰", "严子贤", "孙樾", "曾辉", "舒童",
        "王凯沐", "梁思伟", "张翅", "李世鑫", "卡戎", "程宇峰", "沉思", "蔡欣洋", "时康", "王皓祯",
        "代高政", "姚冠宇", "刘安歌", "刘润铭", "魏尊", "尤洋", "王培延", "马瑞泽", "陈俊儒", "魏华",
        "赵振栋", "刘萧旭", "皮德胜", "杜培源", "鞠振涛", "郭勐", "诸葛迪迪", "李芮", "杨泠铸", "徐金海",
        "孙明文", "任飞跃", "张子唯", "黄旭高", "曾祥羽", "徐成刚", "徐云龙", "王猛", "刘国际", "钟世岩",
        "张振宇", "徐卫龙", "王永新", "宋敬伟", "孙启鑫", "宋金阳", "孙一平", "董勋辉", "张震", "王嘉皓",
        "郑国锋", "李军", "徐大宁", "张正", "李京雨", "乔狄", "张铭祥", "户元松", "何红华", "冷海铭",
        "楚晓龙", "晟铭", "商泽华", "吴昊", "罗大涛", "刘昱辰", "王嘉禾", "陈宇星", "李腾", "叶新龙",
        "祝贺", "王梓豪", "余嘉政", "李治源", "艺丰", "张辉", "张海峰", "潘俊", "廖峰", "汪奇",
        "杨灿", "易浩铭", "六子", "谭鑫", "范少雄")
        private val femaleIntro = Regex("女演员|女艺人|女歌手|女模")
        private val maleIntro = Regex("男演员|男艺人|男歌手|男模")

        fun infer(name: String, intro: String) = when {
            name in femaleNames -> Female
            name in maleNames -> Male
            femaleIntro.containsMatchIn(intro) -> Female
            maleIntro.containsMatchIn(intro) -> Male
            else -> All
        }
    }
}

object HGActorRank {
    fun fetch(client: HongguoClient, offset: Int = 0, sessionId: String = "", startRank: Int = 0): HGActorRankPage {
        val d = client.ensureDevice()
        val query = "background_selected_items=&category_id=0&cell_id=7470092475068071998&cell_sub_id=0" +
            "&client_req_type=2&client_template=2&filter_ids=&gender=2&limit=20&offset=$offset&panel_selected_items=" +
            "&selected_items=ranklist_celebrity&session_id=${GoURL.queryEscape(if (offset == 0) "" else sessionId)}" +
            "&sub_selected_items=ranklist_series_actor&tab_type=${if (offset == 0) 26 else 18}&unlimited_selector_change_type=0&" +
            client.listQuery(d, System.currentTimeMillis())
        val response = client.list(HongguoClient.URL_RANK_CELL + query)
        if (J.str(response.opt("code")) != "0") throw ProviderException.upstream("剧星榜请求失败")
        return parse(J.dict(response.opt("data")), offset, sessionId, startRank)
    }

    fun parse(data: JSONObject, offset: Int = 0, sessionId: String = "", startRank: Int = 0): HGActorRankPage {
        val cv = J.dict(data.opt("cell_view"))
        val outer = J.arr(J.dict(J.dict(cv.opt("cell_selector")).opt("outer_row")).opt("items"))
            .map { J.dict(it) }.firstOrNull { J.bool(it.opt("is_selected")) }
        if (outer != null) {
            val id = J.str(outer.opt("selector_item_id"))
            if (id.isNotEmpty() && id != "ranklist_celebrity") throw ProviderException.upstream("服务端未选中演员榜")
            val sub = J.arr(J.dict(J.dict(outer.opt("sub_cell_selector")).opt("outer_row")).opt("items"))
                .map { J.dict(it) }.firstOrNull { J.bool(it.opt("is_selected")) }
            val subId = J.str(sub?.opt("selector_item_id"))
            if (subId.isNotEmpty() && subId != "ranklist_series_actor") throw ProviderException.upstream("服务端未选中剧星榜")
        }
        fun clean(s: String) = org.jsoup.Jsoup.parse(s).text()
        val actors = arrayListOf<HGRankedActor>()
        for (cell in J.arr(cv.opt("cell_data"))) for (raw in J.arr(J.dict(cell).opt("ugc_user_data"))) {
            val m = J.dict(raw); val base = J.dict(J.dict(m.opt("user_info")).opt("base_info"))
            val id = J.str(base.opt("actor_id_str")); val name = clean(J.str(base.opt("user_name")))
            if (id.isEmpty() || name.isEmpty()) continue
            val change = J.dict(m.opt("rank_change_data"))
            actors += HGRankedActor(id, name, J.str(base.opt("user_avatar")), startRank + actors.size + 1,
                J.str(J.dict(m.opt("rec_text_item")).opt("RecommendText")), clean(J.str(m.opt("intro"))),
                J.arr(m.opt("sub_title_list")).map { clean(J.str(J.dict(it).opt("content"))) }.filter { it.isNotEmpty() },
                J.int(change.opt("rank_change_type")), J.int(change.opt("ChangeNum")))
        }
        val date = runCatching { JSONObject(cv.optString("cell_data_json")).optString("rank_list_date") }.getOrDefault("")
        return HGActorRankPage(actors, date, J.bool(data.opt("has_more")),
            J.int(data.opt("next_offset")).takeIf { it > offset } ?: (offset + actors.size), J.str(data.opt("session_id")).ifEmpty { sessionId })
    }
}
