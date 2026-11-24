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
const COORDINATOR_UID = "coord_uid_01";
const COORDINATOR_2_UID = "coord_uid_02";
const SUPER_ADMIN_UID = "super_admin_uid";

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

function buildBatchPayload(uploaderUid, overrides = {}) {
  const now = pastDate(2000);
  return {
    userId: uploaderUid,
    uploaderName: "coord1 (Coordinator)",
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

function buildQuestionPayload(uploaderUid, batchId, overrides = {}) {
  const now = pastDate(2000);
  return {
    userId: uploaderUid,
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

function coordContext(uid = COORDINATOR_UID, email = "coord1@bsreader.local") {
  return testEnv.authenticatedContext(uid, { email, email_verified: false }).firestore();
}

function superAdminContext() {
  return testEnv.authenticatedContext(SUPER_ADMIN_UID, {
    email: "mrlalon@bsreader.local",
    email_verified: false,
  }).firestore();
}

test("Unauthenticated guests can read questions and submit exam scores, but cannot upload or modify Q&A PDFs", async () => {
  const guestDb = testEnv.unauthenticatedContext().firestore();
  const adminDb = superAdminContext();

  // Super Admin seeds a batch & question
  const seedBatch = adminDb.batch();
  seedBatch.set(adminDb.collection("examPdfBatches").doc("batch_seed"), buildBatchPayload(SUPER_ADMIN_UID));
  seedBatch.set(adminDb.collection("examQuestions").doc("q_seed"), buildQuestionPayload(SUPER_ADMIN_UID, "batch_seed"));
  await assertSucceeds(seedBatch.commit());

  // Guest can read filtered exam bank queries
  await assertSucceeds(guestDb.collection("examPdfBatches").where("visibility", "==", "EXAM_BANK").get());
  await assertSucceeds(guestDb.collection("examQuestions").where("visibility", "==", "EXAM_BANK").get());

  // Guest CANNOT upload batches or questions
  await assertFails(
    guestDb.collection("examPdfBatches").doc("batch_guest").set(buildBatchPayload(DEVICE_CANDIDATE_1))
  );
  await assertFails(
    guestDb.collection("examQuestions").doc("q_guest").set(buildQuestionPayload(DEVICE_CANDIDATE_1, "batch_seed"))
  );

  // Guest CANNOT update or delete existing questions/batches
  await assertFails(
    guestDb.collection("examQuestions").doc("q_seed").update({ active: false, updatedAt: pastDate(500) })
  );
  await assertFails(guestDb.collection("examQuestions").doc("q_seed").delete());
  await assertFails(guestDb.collection("examPdfBatches").doc("batch_seed").delete());

  // Guest CAN submit exam score
  await assertSucceeds(
    guestDb.collection("examSubmissions").doc("sub_guest_1").set(buildSubmissionPayload(DEVICE_CANDIDATE_1))
  );
});

test("Coordinator can upload Q&A PDFs with own UID only, and cannot edit/delete questions, batches, or submissions", async () => {
  const coordDb = coordContext(COORDINATOR_UID, "coord1@bsreader.local");
  const coord2Db = coordContext(COORDINATOR_2_UID, "coord2@bsreader.local");
  const adminDb = superAdminContext();

  // Coordinator CAN upload batch + questions with their own UID
  const wb = coordDb.batch();
  wb.set(coordDb.collection("examPdfBatches").doc("batch_coord_1"), buildBatchPayload(COORDINATOR_UID));
  wb.set(coordDb.collection("examQuestions").doc("q_coord_1"), buildQuestionPayload(COORDINATOR_UID, "batch_coord_1"));
  await assertSucceeds(wb.commit());

  // Coordinator CANNOT spoof someone else's UID
  await assertFails(
    coordDb.collection("examPdfBatches").doc("batch_spoof").set(buildBatchPayload(COORDINATOR_2_UID))
  );

  // Coordinator CANNOT hide, edit, or delete even their own uploaded question or batch
  await assertFails(
    coordDb.collection("examQuestions").doc("q_coord_1").update({ active: false, updatedAt: pastDate(500) })
  );
  await assertFails(coordDb.collection("examQuestions").doc("q_coord_1").delete());
  await assertFails(coordDb.collection("examPdfBatches").doc("batch_coord_1").delete());

  // Coordinator CANNOT delete submissions or block other coordinators
  await assertSucceeds(
    testEnv.unauthenticatedContext().firestore().collection("examSubmissions").doc("sub_1").set(buildSubmissionPayload(DEVICE_CANDIDATE_1))
  );
  await assertFails(coordDb.collection("examSubmissions").doc("sub_1").delete());
  await assertFails(
    coordDb.collection("disabledCoordinators").doc(COORDINATOR_2_UID).set({
      uid: COORDINATOR_2_UID,
      name: "coord2",
      blockedByUid: COORDINATOR_UID,
      createdAt: pastDate(500),
    })
  );

  // Another coordinator can still upload
  const wb2 = coord2Db.batch();
  wb2.set(coord2Db.collection("examPdfBatches").doc("batch_coord_2"), buildBatchPayload(COORDINATOR_2_UID));
  wb2.set(coord2Db.collection("examQuestions").doc("q_coord_2"), buildQuestionPayload(COORDINATOR_2_UID, "batch_coord_2"));
  await assertSucceeds(wb2.commit());

  // Super Admin CAN block coordinator 1, hide/delete questions/batches, and delete submissions
  await assertSucceeds(
    adminDb.collection("disabledCoordinators").doc(COORDINATOR_UID).set({
      uid: COORDINATOR_UID,
      name: "coord1 (Coordinator)",
      blockedByUid: SUPER_ADMIN_UID,
      createdAt: pastDate(500),
    })
  );

  // Blocked coordinator immediately loses upload permission
  await assertFails(
    coordDb.collection("examPdfBatches").doc("batch_blocked").set(buildBatchPayload(COORDINATOR_UID))
  );

  // Super Admin can edit/delete coordinator questions/batches and submissions
  await assertSucceeds(
    adminDb.collection("examQuestions").doc("q_coord_1").update({ active: false, updatedAt: pastDate(400) })
  );
  await assertSucceeds(adminDb.collection("examQuestions").doc("q_coord_1").delete());
  await assertSucceeds(adminDb.collection("examPdfBatches").doc("batch_coord_1").delete());
  await assertSucceeds(adminDb.collection("examSubmissions").doc("sub_1").delete());
});

test("Question validation still blocks invalid MCQ indices, orphaned questions, ghost fields, and future timestamps", async () => {
  const coordDb = coordContext();
  await assertSucceeds(coordDb.collection("examPdfBatches").doc("batch_valid").set(buildBatchPayload(COORDINATOR_UID)));

  await assertFails(
    coordDb.collection("examQuestions").doc("q_orphan").set(buildQuestionPayload(COORDINATOR_UID, "nonexistent_batch"))
  );
  await assertFails(
    coordDb.collection("examQuestions").doc("q_bad_idx").set(
      buildQuestionPayload(COORDINATOR_UID, "batch_valid", { correctOptionIndex: 4 })
    )
  );
  await assertFails(
    coordDb.collection("examQuestions").doc("q_ghost").set(
      buildQuestionPayload(COORDINATOR_UID, "batch_valid", { id: "q_ghost", adminOverride: true })
    )
  );
  await assertFails(
    coordDb.collection("examQuestions").doc("q_future").set(
      buildQuestionPayload(COORDINATOR_UID, "batch_valid", { createdAt: new Date(Date.now() + 120000) })
    )
  );
});

test("Candidate submissions allow ranking queries without sign-in and reject invalid or cross-user tampering", async () => {
  const guestDb = testEnv.unauthenticatedContext().firestore();

  await assertSucceeds(
    guestDb.collection("examSubmissions").doc("sub_cand_1").set(buildSubmissionPayload(DEVICE_CANDIDATE_1))
  );
  await assertSucceeds(guestDb.collection("examSubmissions").where("status", "==", "SUBMITTED").get());
  await assertFails(guestDb.collection("examSubmissions").get());
  await assertFails(
    guestDb.collection("examSubmissions").doc("sub_cand_1").update({ obtainedMarks: 10, percentage: 100, updatedAt: pastDate(500) })
  );
  await assertFails(guestDb.collection("examSubmissions").doc("sub_cand_1").delete());
  await assertFails(
    guestDb.collection("examSubmissions").doc("sub_bad_marks").set(
      buildSubmissionPayload(DEVICE_CANDIDATE_2, { obtainedMarks: 12, totalMarks: 10 })
    )
  );
});

test("Root Super Admin can promote and demote delegated Super Admins, while Coordinators cannot escalate privileges", async () => {
  const adminDb = superAdminContext();
  const leadEmail = "lead.qa@gmail.com";
  const leadUid = "lead_uid_01";
  const leadDb = coordContext(leadUid, leadEmail);
  const coordDb = coordContext(COORDINATOR_UID, "coord1@bsreader.local");

  // Seed a batch & question as Coordinator
  const seed = coordDb.batch();
  seed.set(coordDb.collection("examPdfBatches").doc("batch_promo"), buildBatchPayload(COORDINATOR_UID));
  seed.set(coordDb.collection("examQuestions").doc("q_promo"), buildQuestionPayload(COORDINATOR_UID, "batch_promo"));
  await assertSucceeds(seed.commit());

  // Coordinator CANNOT promote themselves or list superAdmins
  await assertFails(
    coordDb.collection("superAdmins").doc("coord1@bsreader.local").set({
      email: "coord1@bsreader.local",
      addedByEmail: "coord1@bsreader.local",
      addedByUid: COORDINATOR_UID,
      createdAt: pastDate(500),
    })
  );
  await assertFails(coordDb.collection("superAdmins").get());

  // Before promotion, lead.qa@gmail.com is a Coordinator and CANNOT hide questions
  await assertFails(
    leadDb.collection("examQuestions").doc("q_promo").update({ active: false, updatedAt: pastDate(500) })
  );

  // Root Super Admin grants Super Admin access to lead.qa@gmail.com
  await assertSucceeds(
    adminDb.collection("superAdmins").doc(leadEmail).set({
      email: leadEmail,
      addedByEmail: "mrlalon@bsreader.local",
      addedByUid: SUPER_ADMIN_UID,
      createdAt: pastDate(500),
    })
  );

  // Root Super Admin doc ID is protected from modification/deletion in superAdmins
  await assertFails(
    adminDb.collection("superAdmins").doc("mrlalon@bsreader.local").set({
      email: "mrlalon@bsreader.local",
      addedByEmail: "mrlalon@bsreader.local",
      addedByUid: SUPER_ADMIN_UID,
      createdAt: pastDate(500),
    })
  );

  // Delegated Super Admin can now read superAdmins, hide questions, and promote another Super Admin
  await assertSucceeds(leadDb.collection("superAdmins").doc(leadEmail).get());
  await assertSucceeds(leadDb.collection("superAdmins").get());
  await assertSucceeds(
    leadDb.collection("examQuestions").doc("q_promo").update({ active: false, updatedAt: pastDate(400) })
  );
  await assertSucceeds(
    leadDb.collection("superAdmins").doc("backup@outlook.com").set({
      email: "backup@outlook.com",
      addedByEmail: leadEmail,
      addedByUid: leadUid,
      createdAt: pastDate(300),
    })
  );

  // Demoting lead.qa@gmail.com immediately revokes Super Admin privileges
  await assertSucceeds(adminDb.collection("superAdmins").doc(leadEmail).delete());
  await assertFails(
    leadDb.collection("examQuestions").doc("q_promo").update({ active: true, updatedAt: pastDate(200) })
  );
  await assertFails(leadDb.collection("superAdmins").get());
});
