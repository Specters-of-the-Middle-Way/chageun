package kr.drivestep.tutor.gleo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kr.drivestep.tutor.design.PleosUi
import kr.drivestep.tutor.design.PracticeButton

@SuppressLint("ViewConstructor")
class GleoLessonView(context: Context, private val onEnd: () -> Unit, private val onBack: () -> Unit) : LinearLayout(context) {
    private val status = label("", 23f)
    private val connection = label("차근 대화 연결 대기", 18f).apply { contentDescription = "gleo_lesson_connection" }
    private val history = LinearLayout(context).apply { orientation = VERTICAL }
    private val end = PracticeButton(context).apply {
        text = "연수 대화 마치기"; textSize = 20f * PleosUi.TEXT_SCALE; contentDescription = "gleo_lesson_end"
        PleosUi.decorate(this, true)
        setOnClickListener { onEnd() }
    }
    init {
        orientation = VERTICAL
        setPadding(dp(24), dp(18), dp(24), dp(24))
        status.background = cardBackground()
        status.setPadding(dp(20), dp(18), dp(20), dp(18))
        addView(label("글레오와 함께하는 연수", 28f).apply { setTypeface(typeface, Typeface.BOLD) })
        addView(label("‘글레오’라고 부른 뒤, ‘차근 연수 대화 시작해 줘’라고 말씀해 보세요.\n어떤 상황이 궁금한지 답하고, 이유나 순서를 이어서 물어볼 수 있어요.", 19f))
        addView(connection)
        addView(status, LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(12) })
        addView(label("글레오가 듣고 답해요. 답변 뒤 듣기가 끝나면 ‘글레오’라고 다시 불러 주세요.", 17f))
        addView(label("마칠 때는 ‘차근 연수 대화 종료’라고 말하거나 아래 버튼을 눌러 주세요.", 17f))
        addView(history)
        addView(label("아래 내용은 차근이 제공한 학습 요점이에요. 글레오의 실제 답변 표현과는 다를 수 있어요. 차근의 대화 내용은 이 화면에서 임시로만 보관해요.", 16f))
        addView(end, LayoutParams(-1, dp(64)).apply { topMargin = dp(12) })
        addView(PracticeButton(context).apply {
            text = "연수 메뉴로"; textSize = 20f * PleosUi.TEXT_SCALE; contentDescription = "gleo_lesson_back"
            PleosUi.decorate(this); setOnClickListener { onBack() }
        }, LayoutParams(-1, dp(64)).apply { topMargin = dp(10) })
    }
    fun show(session: GleoLessonSession, reply: GleoReply? = null, receivedFromGleo: Boolean = false) {
        connection.text = when {
            session.ended -> "차근 연수 대화를 마쳤어요."
            receivedFromGleo -> "차근에 요청이 도착했어요. 아래 학습 요점을 글레오에 전달했어요."
            else -> "차근 대화 연결 대기 · 글레오에게 연수 대화를 시작해 달라고 말씀해 주세요."
        }
        status.text = reply?.display ?: "궁금한 운전 상황을 편하게 말씀해 주세요."
        status.contentDescription = "gleo_lesson_reply"
        history.removeAllViews()
        session.turns.takeLast(3).forEach { turn ->
            val card = LinearLayout(context).apply {
                orientation = VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12)); background = cardBackground()
            }
            card.addView(label("내 질문 · ${turn.question}", 18f))
            card.addView(label("${turn.topic} · ${turn.guidance}", 21f))
            history.addView(card, LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        end.visibility = if (session.ended) View.GONE else View.VISIBLE
    }
    private fun label(value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size * PleosUi.TEXT_SCALE; setTextColor(PleosUi.TEXT); setPadding(0, dp(8), 0, dp(8))
    }
    private fun cardBackground() = GradientDrawable().apply { setColor(PleosUi.SURFACE); cornerRadius = dp(18).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
