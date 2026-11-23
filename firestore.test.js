const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const DEVICE_CANDIDATE_1 = "cand_device_01";
const DEVICE_CANDIDATE_2 = "cand_device_02";
const ADMIN_UID = "admin_uid_03";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(msOffset = 2000) {
  return new Date(Date.now() - msOffset);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function buildBatchPayload(participantId, overrides = {}) {
  const now = pastDate(2000);
  return {
    userId: participantId,
    uploaderName: "Exam Coordinator",
    title: "Quality Manual Q&A Set 1",
    fileName: "quality_qa_1.pdf",
    pageCount: 6,
    questionCount: 3,
    mcqCount: 1,
    fillCount: 1,
    descriptiveCount: 1,
    visibility: "EXAM_BANK",
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function buildQuestionPayload(participantId, batchId, overrides = {}) {
  const now = pastDate(2000);
  return {
    userId: participantId,
    batchId,
    batchTitle: "Quality Manual Q&A Set 1",
    questionType: "MCQ",
    orderIndex: 0,
    prompt: "Which AQL level is standard for major defects?",
    options: ["0.65", "1.5", "2.5", "4.0"],
    correctOptionIndex: 2,
    correctAnswer: "2.5",
    marks: 1,
    active: true,
    visibility: "EXAM_BANK",
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function buildSubmissionPayload(participantId, overrides = {}) {
  const now = pastDate(2000);
  return {
    userId: participantId,
    candidateName: "Mohidur Rahman",
    phase: "AQC - 10th Phase",
    factoryName: "GMS Composite Knitting Ind. Ltd.",
    obtainedMarks: 7.5,
    totalMarks: 10,
    percentage: 75.0,
    questionCount: 3,
    mcqCorrect: 1,
    mcqTotal: 1,
    fillMarks: 2,
    fillTotalMarks: 2,
    descMarks: 4.5,
    descTotalMarks: 7,
    status: "SUBMITTED",
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

test("Candidates without sign-in can upload Q&A PDFs, read exam questions, and submit exam scores", async () => {
  const guestDb = testEnv.unauthenticatedContext().firestore();
  const batchRef = guestDb.collection("examPdfBatches").doc("batch_01");

  const writeBatch = guestDb.batch();
  writeBatch.set(batchRef, buildBatchPayload(DEVICE_CANDIDATE_1));
  writeBatch.set(guestDb.collection("examQuestions").doc("q_mcq_1"), buildQuestionPayload(DEVICE_CANDIDATE_1, "batch_01"));
  writeBatch.set(
    guestDb.collection("examQuestions").doc("q_fill_1"),
    buildQuestionPayload(DEVICE_CANDIDATE_1, "batch_01", {
      questionType: "FILL_IN_BLANK",
      orderIndex: 1,
      prompt: "Needle detection sensitivity calibration must be checked every ____ hours.",
      options: [],
      correctOptionIndex: -1,
      correctAnswer: "2",
      marks: 2,
    })
  );
  writeBatch.set(
    guestDb.collection("examQuestions").doc("q_desc_1"),
    buildQuestionPayload(DEVICE_CANDIDATE_1, "batch_01", {
      questionType: "DESCRIPTIVE",
      orderIndex: 2,
      prompt: "Describe the corrective procedure when a broken needle tip is missing.",
      options: [],
      correctOptionIndex: -1,
      correctAnswer: "Stop production immediately, quarantine garment batch, scan with handheld detector, locate all fragments before resuming.",
      marks: 5,
    })
  );
  await assertSucceeds(writeBatch.commit());

  // Append a second PDF batch later without sign-in
  const batch2Ref = guestDb.collection("examPdfBatches").doc("batch_02");
  const writeBatch2 = guestDb.batch();
  writeBatch2.set(batch2Ref, buildBatchPayload(DEVICE_CANDIDATE_1, { title: "Mold Control Q&A" }));
  writeBatch2.set(
    guestDb.collection("examQuestions").doc("q_mcq_2"),
    buildQuestionPayload(DEVICE_CANDIDATE_1, "batch_02", { batchTitle: "Mold Control Q&A", orderIndex: 0 })
  );
  await assertSucceeds(writeBatch2.commit());

  // Filtered Kotlin queries succeed
  await assertSucceeds(guestDb.collection("examPdfBatches").where("visibility", "==", "EXAM_BANK").get());
  await assertSucceeds(guestDb.collection("examQuestions").where("visibility", "==", "EXAM_BANK").get());

  // Unfiltered list queries fail
  await assertFails(guestDb.collection("examPdfBatches").get());
  await assertFails(guestDb.collection("examQuestions").get());

  // Toggle question visibility and delete works
  await assertSucceeds(guestDb.collection("examQuestions").doc("q_mcq_2").update({ active: false, updatedAt: pastDate(500) }));
  await assertSucceeds(guestDb.collection("examQuestions").doc("q_mcq_2").delete());
});

test("Question validation blocks invalid MCQ indices, orphaned questions, ghost fields, and future timestamps", async () => {
  const guestDb = testEnv.unauthenticatedContext().firestore();
  await assertSucceeds(guestDb.collection("examPdfBatches").doc("batch_valid").set(buildBatchPayload(DEVICE_CANDIDATE_1)));

  // Orphaned batchId fails
  await assertFails(
    guestDb.collection("examQuestions").doc("q_orphan").set(buildQuestionPayload(DEVICE_CANDIDATE_1, "nonexistent_batch"))
  );

  // Invalid MCQ index out of bounds fails
  await assertFails(
    guestDb.collection("examQuestions").doc("q_bad_idx").set(
      buildQuestionPayload(DEVICE_CANDIDATE_1, "batch_valid", { correctOptionIndex: 4 })
    )
  );

  // Extra ghost field fails
  await assertFails(
    guestDb.collection("examQuestions").doc("q_ghost").set(
      buildQuestionPayload(DEVICE_CANDIDATE_1, "batch_valid", { id: "q_ghost", adminOverride: true })
    )
  );

  // Future timestamp tampering fails
  await assertFails(
    guestDb.collection("examQuestions").doc("q_future").set(
      buildQuestionPayload(DEVICE_CANDIDATE_1, "batch_valid", { createdAt: new Date(Date.now() + 120000) })
    )
  );
});

test("Candidate submissions allow ranking queries without sign-in and lock terminal scores against tampering or deletion", async () => {
  const guestDb = testEnv.unauthenticatedContext().firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: "qureshialrajin@gmail.com",
    email_verified: true,
  }).firestore();

  const subId = "sub_cand_1";
  await assertSucceeds(
    guestDb.collection("examSubmissions").doc(subId).set(buildSubmissionPayload(DEVICE_CANDIDATE_1))
  );

  // Another candidate can list submitted scores to compute rank across total candidates
  await assertSucceeds(guestDb.collection("examSubmissions").where("status", "==", "SUBMITTED").get());

  // Unfiltered submissions list fails
  await assertFails(guestDb.collection("examSubmissions").get());

  // Candidate cannot tamper with or delete submitted scores (terminal state lock)
  await assertFails(
    guestDb.collection("examSubmissions").doc(subId).update({ obtainedMarks: 10, percentage: 100, updatedAt: pastDate(500) })
  );
  await assertFails(guestDb.collection("examSubmissions").doc(subId).delete());

  // Invalid score exceeding totalMarks is rejected
  await assertFails(
    guestDb.collection("examSubmissions").doc("sub_bad_marks").set(
      buildSubmissionPayload(DEVICE_CANDIDATE_2, { obtainedMarks: 12, totalMarks: 10 })
    )
  );

  // Admin can remove a test submission if needed
  await assertSucceeds(adminDb.collection("examSubmissions").doc(subId).delete());
});

test("Private users/{userId}/examReviews remains locked against unauthenticated reads or writes", async () => {
  const guestDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(guestDb.collection("users").doc(DEVICE_CANDIDATE_1).collection("examReviews").get());
  await assertFails(
    guestDb.collection("users").doc(DEVICE_CANDIDATE_1).collection("examReviews").doc("sub_1").set({
      submissionId: "sub_1",
      candidateName: "Test User",
      phase: "Phase 1",
      factoryName: "Factory A",
      obtainedMarks: 5,
      totalMarks: 10,
      items: [{ questionId: "q1" }],
      createdAt: pastDate(1000),
    })
  );
});
