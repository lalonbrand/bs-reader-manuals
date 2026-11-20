const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_01";
const BOB_UID = "bob_uid_02";
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

function buildBatchPayload(uid, overrides = {}) {
  const now = pastDate(2000);
  return {
    userId: uid,
    uploaderName: "Alice Tester",
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

function buildQuestionPayload(uid, batchId, overrides = {}) {
  const now = pastDate(2000);
  return {
    userId: uid,
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

function buildSubmissionPayload(uid, overrides = {}) {
  const now = pastDate(2000);
  return {
    userId: uid,
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

test("Unauthenticated users cannot read or write any exam collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("examPdfBatches").where("visibility", "==", "EXAM_BANK").get());
  await assertFails(unauthDb.collection("examQuestions").where("visibility", "==", "EXAM_BANK").get());
  await assertFails(unauthDb.collection("examSubmissions").where("status", "==", "SUBMITTED").get());
  await assertFails(unauthDb.collection("examPdfBatches").doc("batch_1").set(buildBatchPayload(ALICE_UID)));
});

test("Atomic WriteBatch creates ExamPdfBatch and all 3 question types; subsequent PDF upload appends", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const batchRef = aliceDb.collection("examPdfBatches").doc("batch_01");

  const writeBatch = aliceDb.batch();
  writeBatch.set(batchRef, buildBatchPayload(ALICE_UID));
  writeBatch.set(aliceDb.collection("examQuestions").doc("q_mcq_1"), buildQuestionPayload(ALICE_UID, "batch_01"));
  writeBatch.set(
    aliceDb.collection("examQuestions").doc("q_fill_1"),
    buildQuestionPayload(ALICE_UID, "batch_01", {
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
    aliceDb.collection("examQuestions").doc("q_desc_1"),
    buildQuestionPayload(ALICE_UID, "batch_01", {
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

  // Append a second PDF batch later
  const batch2Ref = aliceDb.collection("examPdfBatches").doc("batch_02");
  const writeBatch2 = aliceDb.batch();
  writeBatch2.set(batch2Ref, buildBatchPayload(ALICE_UID, { title: "Mold Control Q&A" }));
  writeBatch2.set(
    aliceDb.collection("examQuestions").doc("q_mcq_2"),
    buildQuestionPayload(ALICE_UID, "batch_02", { batchTitle: "Mold Control Q&A", orderIndex: 0 })
  );
  await assertSucceeds(writeBatch2.commit());

  // Filtered Kotlin queries succeed
  await assertSucceeds(aliceDb.collection("examPdfBatches").where("visibility", "==", "EXAM_BANK").get());
  await assertSucceeds(aliceDb.collection("examQuestions").where("visibility", "==", "EXAM_BANK").get());

  // Unfiltered list queries fail
  await assertFails(aliceDb.collection("examPdfBatches").get());
  await assertFails(aliceDb.collection("examQuestions").get());
});

test("Question validation blocks invalid MCQ indices, orphaned questions, ghost fields, and future timestamps", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(aliceDb.collection("examPdfBatches").doc("batch_valid").set(buildBatchPayload(ALICE_UID)));

  // Orphaned batchId fails
  await assertFails(
    aliceDb.collection("examQuestions").doc("q_orphan").set(buildQuestionPayload(ALICE_UID, "nonexistent_batch"))
  );

  // Invalid MCQ index out of bounds fails
  await assertFails(
    aliceDb.collection("examQuestions").doc("q_bad_idx").set(
      buildQuestionPayload(ALICE_UID, "batch_valid", { correctOptionIndex: 4 })
    )
  );

  // Extra ghost field fails
  await assertFails(
    aliceDb.collection("examQuestions").doc("q_ghost").set(
      buildQuestionPayload(ALICE_UID, "batch_valid", { id: "q_ghost", adminOverride: true })
    )
  );

  // Future timestamp tampering fails
  await assertFails(
    aliceDb.collection("examQuestions").doc("q_future").set(
      buildQuestionPayload(ALICE_UID, "batch_valid", { createdAt: new Date(Date.now() + 120000) })
    )
  );
});

test("Cross-user protections on questions/batches vs admin override", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: "qureshialrajin@gmail.com",
    email_verified: true,
  }).firestore();

  await assertSucceeds(aliceDb.collection("examPdfBatches").doc("batch_a").set(buildBatchPayload(ALICE_UID)));
  await assertSucceeds(aliceDb.collection("examQuestions").doc("q_a").set(buildQuestionPayload(ALICE_UID, "batch_a")));

  // Bob can read questions to take the exam
  await assertSucceeds(bobDb.collection("examQuestions").where("visibility", "==", "EXAM_BANK").get());

  // Bob cannot update or delete Alice's question or batch
  await assertFails(bobDb.collection("examQuestions").doc("q_a").update({ active: false, updatedAt: pastDate(500) }));
  await assertFails(bobDb.collection("examQuestions").doc("q_a").delete());
  await assertFails(bobDb.collection("examPdfBatches").doc("batch_a").delete());

  // Alice can toggle her own question
  await assertSucceeds(aliceDb.collection("examQuestions").doc("q_a").update({ active: false, updatedAt: pastDate(500) }));

  // Verified admin email can also manage questions
  await assertSucceeds(adminDb.collection("examQuestions").doc("q_a").update({ marks: 3, updatedAt: pastDate(400) }));
});

test("Candidate submissions allow ranking queries, lock terminal scores, and isolate private examReviews", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  const subId = "sub_alice_1";
  const batch = aliceDb.batch();
  batch.set(aliceDb.collection("examSubmissions").doc(subId), buildSubmissionPayload(ALICE_UID));
  batch.set(
    aliceDb.collection("users").doc(ALICE_UID).collection("examReviews").doc(subId),
    {
      submissionId: subId,
      candidateName: "Mohidur Rahman",
      phase: "AQC - 10th Phase",
      factoryName: "GMS Composite Knitting Ind. Ltd.",
      obtainedMarks: 7.5,
      totalMarks: 10,
      items: [
        {
          questionId: "q_1",
          questionType: "MCQ",
          prompt: "Sample?",
          userAnswer: "2.5",
          referenceAnswer: "2.5",
          maxMarks: 1,
          awardedMarks: 1,
          feedback: "Exact match",
        },
      ],
      createdAt: pastDate(2000),
    }
  );
  await assertSucceeds(batch.commit());

  // Bob can list submitted scores to calculate rank across total candidates
  await assertSucceeds(bobDb.collection("examSubmissions").where("status", "==", "SUBMITTED").get());

  // Unfiltered submissions list fails
  await assertFails(bobDb.collection("examSubmissions").get());

  // Alice cannot tamper with her submitted score after submission (terminal state lock)
  await assertFails(
    aliceDb.collection("examSubmissions").doc(subId).update({ obtainedMarks: 10, percentage: 100, updatedAt: pastDate(500) })
  );

  // Bob CANNOT read or write Alice's private examReviews
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("examReviews").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("examReviews").doc(subId).get());

  // Alice CAN read her own examReviews
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("examReviews").get());
});
