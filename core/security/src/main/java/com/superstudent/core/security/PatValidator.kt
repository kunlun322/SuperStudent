package com.superstudent.core.security

/**
 * Shape check for the shared Personal Access Token before it reaches secure storage.
 *
 * The platform issues `pt-` + a 24-char body + `_` + a lowercase UUID (64 chars). The tail is easy
 * to lose: `_` is a Markdown escape character, so a token read out of rendered issue text arrives as
 * the 27-char prefix, which still passes a naive `startsWith("pt-")` gate. Storing that prefix costs
 * a `TOKEN_INVALID` on the first network call — minutes later, with the bad token already in the
 * Keystore and nothing pointing at truncation as the cause. Rejecting it here keeps it out.
 */
object PatValidator {

    const val EXPECTED_LENGTH = 64

    private val FULL = Regex(
        "^pt-[A-Za-z0-9]{24}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    )
    private val BODY_WITHOUT_TAIL = Regex("^pt-[A-Za-z0-9]{8,}$")

    /** Returns `null` when [raw] is a well-formed PAT, otherwise a user-readable Chinese reason. */
    fun validate(raw: String): String? {
        val pat = raw.trim()
        if (pat.isEmpty()) return "请输入访问令牌"
        if (pat.any { it.isWhitespace() }) return "访问令牌中不能包含空格或换行，请重新复制粘贴"
        if (!pat.startsWith("pt-")) return "访问令牌需以 pt- 开头"
        if (FULL.matches(pat)) return null
        if (BODY_WITHOUT_TAIL.matches(pat)) {
            return "访问令牌不完整：只有 ${pat.length} 位，应为 $EXPECTED_LENGTH 位。" +
                "下划线及其后的部分被截断了，请重新复制完整令牌"
        }
        return "访问令牌格式不正确：应为 pt- 开头的 $EXPECTED_LENGTH 位令牌"
    }
}
