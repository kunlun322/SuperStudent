package com.superstudent.app.features.learn

import java.net.URLEncoder

/**
 * FR-11, link-style handoff only (design §7.3).
 *
 * The platform shared library is NOT reachable from this app: the fixed Vault's headless CLI was
 * never proven to mount a read-only shared Notebook per student, so there is no retrieval here and
 * nothing to keep isolated. What ships is a card per discipline that hands off to an existing free
 * course platform in the system browser, plus a card per learning package keyed on the student's own
 * title. Both the authority docs leave the concrete course catalog unspecified, so the entries are
 * discipline-level deep links into platforms that are genuinely free, not invented course records.
 */
object CourseCatalog {

    private const val ICOOURSE = "https://www.icourse163.org"

    /** 国家高等教育智慧教育平台, official and free; used as the second hand-off target. */
    const val SMART_EDU = "https://higher.smartedu.cn/"

    data class Discipline(val key: String, val label: String, val sample: String)

    /** Slugs verified live against icourse163 on 2026-09-30; an unverified slug would be a dead card. */
    val DISCIPLINES = listOf(
        Discipline("computer", "计算机", "数据结构 · 操作系统 · 计算机网络"),
        Discipline("science", "理学", "高等数学 · 线性代数 · 大学物理"),
        Discipline("engineering", "工学", "电路 · 材料力学 · 工程制图"),
        Discipline("economy", "经济学", "微观经济学 · 宏观经济学 · 计量经济学"),
        Discipline("management", "管理学", "管理学原理 · 会计学 · 市场营销"),
        Discipline("law", "法学", "法理学 · 民法 · 刑法"),
        Discipline("medicine", "医学", "人体解剖学 · 生理学 · 病理学"),
        Discipline("literature", "文学", "中国现代文学 · 古代汉语 · 写作"),
        Discipline("history", "历史学", "中国古代史 · 世界近代史"),
        Discipline("philosophy", "哲学", "马克思主义基本原理 · 逻辑学"),
        Discipline("education", "教育学", "教育学原理 · 教育心理学"),
        Discipline("psychology", "心理学", "普通心理学 · 发展心理学"),
        Discipline("foreign", "外语", "大学英语 · 日语 · 翻译"),
        Discipline("art", "艺术学", "艺术概论 · 美术鉴赏 · 音乐"),
        Discipline("agriculture", "农学", "植物学 · 土壤学"),
        Discipline("military", "军事学", "军事理论"),
    )

    fun categoryUrl(key: String): String = "$ICOOURSE/category/$key"

    /** Subject search, so a learning package title becomes a query rather than a fabricated match. */
    fun searchUrl(keyword: String): String =
        "$ICOOURSE/search.htm?search=" + URLEncoder.encode(keyword.trim(), "UTF-8")
}
