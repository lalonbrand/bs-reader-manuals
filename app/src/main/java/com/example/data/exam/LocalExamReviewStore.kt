package com.example.data.exam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val PREFS_NAME = "bs_exam_device_prefs"
private const val KEY_PARTICIPANT_ID = "participant_id"
private const val KEY_REVIEWS_JSON = "saved_exam_reviews"
private const val KEY_LAST_NAME = "last_candidate_name"
private const val KEY_LAST_PHASE = "last_candidate_phase"
private const val KEY_LAST_FACTORY = "last_candidate_factory"
private const val MAX_LOCAL_REVIEWS = 40

data class SavedCandidateProfile(
    val candidateName: String = "",
    val phase: String = "",
    val factoryName: String = ""
)

class LocalExamReviewStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreateParticipantId(): String {
        val existing = prefs.getString(KEY_PARTICIPANT_ID, null)?.trim()
        if (!existing.isNullOrBlank()) return existing
        val generated = "cand_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        prefs.edit().putString(KEY_PARTICIPANT_ID, generated).apply()
        return generated
    }

    fun loadCandidateProfile(): SavedCandidateProfile {
        return SavedCandidateProfile(
            candidateName = prefs.getString(KEY_LAST_NAME, "").orEmpty(),
            phase = prefs.getString(KEY_LAST_PHASE, "").orEmpty(),
            factoryName = prefs.getString(KEY_LAST_FACTORY, "").orEmpty()
        )
    }

    fun saveCandidateProfile(name: String, phase: String, factoryName: String) {
        prefs.edit()
            .putString(KEY_LAST_NAME, name.trim().take(100))
            .putString(KEY_LAST_PHASE, phase.trim().take(100))
            .putString(KEY_LAST_FACTORY, factoryName.trim().take(150))
            .apply()
    }

    fun saveReview(review: ExamReview) {
        val updated = (listOf(review) + getReviews().filterNot { it.submissionId == review.submissionId })
            .take(MAX_LOCAL_REVIEWS)
        val arr = JSONArray()
        updated.forEach { rev ->
            val itemsArr = JSONArray()
            rev.items.forEach { item ->
                itemsArr.put(
                    JSONObject().apply {
                        put("questionId", item.questionId)
                        put("questionType", item.questionType)
                        put("prompt", item.prompt)
                        put("userAnswer", item.userAnswer)
                        put("referenceAnswer", item.referenceAnswer)
                        put("maxMarks", item.maxMarks)
                        put("awardedMarks", item.awardedMarks)
                        put("feedback", item.feedback)
                    }
                )
            }
            arr.put(
                JSONObject().apply {
                    put("submissionId", rev.submissionId)
                    put("candidateName", rev.candidateName)
                    put("phase", rev.phase)
                    put("factoryName", rev.factoryName)
                    put("obtainedMarks", rev.obtainedMarks)
                    put("totalMarks", rev.totalMarks)
                    put("items", itemsArr)
                }
            )
        }
        prefs.edit().putString(KEY_REVIEWS_JSON, arr.toString()).apply()
    }

    fun getReviews(): List<ExamReview> {
        val raw = prefs.getString(KEY_REVIEWS_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val itemsArr = obj.optJSONArray("items") ?: JSONArray()
                    val items = buildList {
                        for (j in 0 until itemsArr.length()) {
                            val itemObj = itemsArr.optJSONObject(j) ?: continue
                            add(
                                GradedAnswerItem(
                                    questionId = itemObj.optString("questionId"),
                                    questionType = itemObj.optString("questionType", QuestionType.MCQ.wireValue),
                                    prompt = itemObj.optString("prompt"),
                                    userAnswer = itemObj.optString("userAnswer"),
                                    referenceAnswer = itemObj.optString("referenceAnswer"),
                                    maxMarks = itemObj.optLong("maxMarks", 1L),
                                    awardedMarks = itemObj.optDouble("awardedMarks", 0.0),
                                    feedback = itemObj.optString("feedback")
                                )
                            )
                        }
                    }
                    val subId = obj.optString("submissionId")
                    if (subId.isNotBlank()) {
                        add(
                            ExamReview(
                                id = subId,
                                submissionId = subId,
                                candidateName = obj.optString("candidateName"),
                                phase = obj.optString("phase"),
                                factoryName = obj.optString("factoryName"),
                                obtainedMarks = obj.optDouble("obtainedMarks", 0.0),
                                totalMarks = obj.optLong("totalMarks", 1L),
                                items = items
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
