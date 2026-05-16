/// Domain-modellen, plat geserialiseerd vanaf de JSON-API
/// (zie deploy/status-dashboard/app.py — /api/v1/*).
///
/// Bewust losse fromJson-factories i.p.v. een codegen-laag — kleiner +
/// makkelijk te lezen voor zes schermen.

class StoryRow {
  final String storyKey;
  final String? startedAt;
  final String? endedAt;
  final String finalStatus;
  final int runCount;
  final int durationMsSum;
  final int input;
  final int output;
  final int cacheRead;
  final int cacheCreation;
  final double costUsd;

  StoryRow({
    required this.storyKey,
    required this.startedAt,
    required this.endedAt,
    required this.finalStatus,
    required this.runCount,
    required this.durationMsSum,
    required this.input,
    required this.output,
    required this.cacheRead,
    required this.cacheCreation,
    required this.costUsd,
  });

  factory StoryRow.fromJson(Map<String, dynamic> j) => StoryRow(
        storyKey: j['story_key'] as String? ?? '',
        startedAt: j['started_at'] as String?,
        endedAt: j['ended_at'] as String?,
        finalStatus: j['final_status'] as String? ?? '',
        runCount: (j['run_count'] as num?)?.toInt() ?? 0,
        durationMsSum: (j['duration_ms_sum'] as num?)?.toInt() ?? 0,
        input: (j['input'] as num?)?.toInt() ?? 0,
        output: (j['output'] as num?)?.toInt() ?? 0,
        cacheRead: (j['cache_read'] as num?)?.toInt() ?? 0,
        cacheCreation: (j['cache_creation'] as num?)?.toInt() ?? 0,
        costUsd: (j['cost_usd'] as num?)?.toDouble() ?? 0.0,
      );
}

class AgentRun {
  final int id;
  final String role;
  final String jobName;
  final String model;
  final String effort;
  final int? level;
  final String? startedAt;
  final String? endedAt;
  final String outcome;
  final int input;
  final int output;
  final int cacheRead;
  final int cacheCreation;
  final double costUsd;
  final int numTurns;
  final int durationMs;
  final String summaryText;

  AgentRun({
    required this.id,
    required this.role,
    required this.jobName,
    required this.model,
    required this.effort,
    required this.level,
    required this.startedAt,
    required this.endedAt,
    required this.outcome,
    required this.input,
    required this.output,
    required this.cacheRead,
    required this.cacheCreation,
    required this.costUsd,
    required this.numTurns,
    required this.durationMs,
    required this.summaryText,
  });

  factory AgentRun.fromJson(Map<String, dynamic> j) => AgentRun(
        id: (j['id'] as num?)?.toInt() ?? 0,
        role: j['role'] as String? ?? '',
        jobName: j['job_name'] as String? ?? '',
        model: j['model'] as String? ?? '',
        effort: j['effort'] as String? ?? '',
        level: (j['level'] as num?)?.toInt(),
        startedAt: j['started_at'] as String?,
        endedAt: j['ended_at'] as String?,
        outcome: j['outcome'] as String? ?? '',
        input: (j['input'] as num?)?.toInt() ?? 0,
        output: (j['output'] as num?)?.toInt() ?? 0,
        cacheRead: (j['cache_read'] as num?)?.toInt() ?? 0,
        cacheCreation: (j['cache_creation'] as num?)?.toInt() ?? 0,
        costUsd: (j['cost_usd'] as num?)?.toDouble() ?? 0.0,
        numTurns: (j['num_turns'] as num?)?.toInt() ?? 0,
        durationMs: (j['duration_ms'] as num?)?.toInt() ?? 0,
        summaryText: j['summary_text'] as String? ?? '',
      );
}

class StoryDetail {
  final int id;
  final String storyKey;
  final String? startedAt;
  final String? endedAt;
  final String? finalStatus;
  final Map<String, dynamic> totals;
  final List<AgentRun> runs;
  final String jiraTitle;
  final String jiraStatus;
  final String aiPhase;
  final List<Map<String, dynamic>> prs;
  final List<Map<String, dynamic>> commits;
  final List<BuildRun> prBuilds;

  StoryDetail({
    required this.id,
    required this.storyKey,
    required this.startedAt,
    required this.endedAt,
    required this.finalStatus,
    required this.totals,
    required this.runs,
    required this.jiraTitle,
    required this.jiraStatus,
    required this.aiPhase,
    required this.prs,
    required this.commits,
    required this.prBuilds,
  });

  factory StoryDetail.fromJson(Map<String, dynamic> j) {
    final story = (j['story'] as Map?) ?? {};
    return StoryDetail(
      id: (story['id'] as num?)?.toInt() ?? 0,
      storyKey: story['story_key'] as String? ?? '',
      startedAt: story['started_at'] as String?,
      endedAt: story['ended_at'] as String?,
      finalStatus: story['final_status'] as String?,
      totals: Map<String, dynamic>.from(story['totals'] as Map? ?? {}),
      runs: (story['runs'] as List? ?? [])
          .map((r) => AgentRun.fromJson(Map<String, dynamic>.from(r as Map)))
          .toList(),
      jiraTitle: j['jira_title'] as String? ?? '',
      jiraStatus: j['jira_status'] as String? ?? '',
      aiPhase: j['ai_phase'] as String? ?? '',
      prs: (j['prs'] as List? ?? [])
          .map((p) => Map<String, dynamic>.from(p as Map))
          .toList(),
      commits: (j['commits'] as List? ?? [])
          .map((c) => Map<String, dynamic>.from(c as Map))
          .toList(),
      prBuilds: (j['pr_builds'] as List? ?? [])
          .map((r) => BuildRun.fromJson(Map<String, dynamic>.from(r as Map)))
          .toList(),
    );
  }
}

/// Eén iteratie van een agent met eindconclusie. De backend stuurt deze
/// los van [AgentRun]'s full model — alleen wat de briefing nodig heeft.
class HandoverRun {
  final int id;
  final String role;
  final String? startedAt;
  final String? endedAt;
  final String outcome;
  final String summaryText;
  final String verdict;  // 'OK' / 'CHANGES' / 'PASS' / 'FAIL' / ''
  final bool hadQuestion; // true als de agent eindigde met een PO-vraag

  HandoverRun({
    required this.id,
    required this.role,
    required this.startedAt,
    required this.endedAt,
    required this.outcome,
    required this.summaryText,
    required this.verdict,
    required this.hadQuestion,
  });

  factory HandoverRun.fromJson(Map<String, dynamic> j) => HandoverRun(
        id: (j['id'] as num?)?.toInt() ?? 0,
        role: j['role'] as String? ?? '',
        startedAt: j['started_at'] as String?,
        endedAt: j['ended_at'] as String?,
        outcome: j['outcome'] as String? ?? '',
        summaryText: j['summary_text'] as String? ?? '',
        verdict: j['verdict'] as String? ?? '',
        hadQuestion: j['had_question'] as bool? ?? false,
      );
}

class PoDialogueEntry {
  final String agent;            // refiner / reviewer / tester
  final String questionText;
  final String? questionCreated;
  final String answerText;
  final String? answerCreated;

  PoDialogueEntry({
    required this.agent,
    required this.questionText,
    required this.questionCreated,
    required this.answerText,
    required this.answerCreated,
  });

  bool get hasAnswer => answerText.trim().isNotEmpty;

  factory PoDialogueEntry.fromJson(Map<String, dynamic> j) => PoDialogueEntry(
        agent: j['agent'] as String? ?? '',
        questionText: j['question_text'] as String? ?? '',
        questionCreated: j['question_created'] as String?,
        answerText: j['answer_text'] as String? ?? '',
        answerCreated: j['answer_created'] as String?,
      );
}

class HandoverData {
  final String storyKey;
  final String jiraTitle;
  final List<HandoverRun> refiner;
  final List<HandoverRun> developer;
  final List<HandoverRun> reviewer;
  final List<HandoverRun> tester;
  final List<PoDialogueEntry> poDialogue;

  HandoverData({
    required this.storyKey,
    required this.jiraTitle,
    required this.refiner,
    required this.developer,
    required this.reviewer,
    required this.tester,
    required this.poDialogue,
  });

  factory HandoverData.fromJson(Map<String, dynamic> j) {
    List<HandoverRun> runs(String key) {
      final raw = j[key];
      if (raw is! List) return [];
      return raw
          .map((r) => HandoverRun.fromJson(Map<String, dynamic>.from(r as Map)))
          .toList();
    }

    return HandoverData(
      storyKey: j['story_key'] as String? ?? '',
      jiraTitle: j['jira_title'] as String? ?? '',
      refiner: runs('refiner'),
      developer: runs('developer'),
      reviewer: runs('reviewer'),
      tester: runs('tester'),
      poDialogue: (j['po_dialogue'] as List? ?? [])
          .map((e) => PoDialogueEntry.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList(),
    );
  }
}

class ApkInfo {
  final ApkEntry pnf;
  final ApkEntry dashboard;
  ApkInfo({required this.pnf, required this.dashboard});

  factory ApkInfo.fromJson(Map<String, dynamic> j) => ApkInfo(
        pnf: ApkEntry.fromJson(
            Map<String, dynamic>.from(j['pnf'] as Map? ?? {})),
        dashboard: ApkEntry.fromJson(
            Map<String, dynamic>.from(j['dashboard'] as Map? ?? {})),
      );
}

class ApkEntry {
  final String url;
  final String? builtAt;     // ISO timestamp, of null als onbekend
  final int size;            // bytes
  final String tag;          // bv. 'apk-20260515-...' voor PNF
  ApkEntry({required this.url, this.builtAt, this.size = 0, this.tag = ''});

  factory ApkEntry.fromJson(Map<String, dynamic> j) => ApkEntry(
        url: j['url'] as String? ?? '',
        builtAt: j['built_at'] as String?,
        size: (j['size'] as num?)?.toInt() ?? 0,
        tag: j['tag'] as String? ?? '',
      );
}

class BuildRun {
  final int id;
  final String name;
  final String status;     // queued / in_progress / completed
  final String conclusion; // success / failure / cancelled / skipped / ''
  final String htmlUrl;
  final String createdAt;
  final String updatedAt;
  final String event;
  final String headSha;
  final String age;

  BuildRun({
    required this.id,
    required this.name,
    required this.status,
    required this.conclusion,
    required this.htmlUrl,
    required this.createdAt,
    required this.updatedAt,
    required this.event,
    required this.headSha,
    required this.age,
  });

  factory BuildRun.fromJson(Map<String, dynamic> j) => BuildRun(
        id: (j['id'] as num?)?.toInt() ?? 0,
        name: j['name'] as String? ?? '',
        status: j['status'] as String? ?? '',
        conclusion: j['conclusion'] as String? ?? '',
        htmlUrl: j['html_url'] as String? ?? '',
        createdAt: j['created_at'] as String? ?? '',
        updatedAt: j['updated_at'] as String? ?? '',
        event: j['event'] as String? ?? '',
        headSha: j['head_sha'] as String? ?? '',
        age: j['age'] as String? ?? '',
      );
}

class ScreenshotAttachment {
  final String id;
  final String filename;
  final String mimeType;
  final int size;
  final String created;
  final String rawUrl;

  ScreenshotAttachment({
    required this.id,
    required this.filename,
    required this.mimeType,
    required this.size,
    required this.created,
    required this.rawUrl,
  });

  factory ScreenshotAttachment.fromJson(Map<String, dynamic> j) =>
      ScreenshotAttachment(
        id: j['id'] as String? ?? '',
        filename: j['filename'] as String? ?? '',
        mimeType: j['mime_type'] as String? ?? '',
        size: (j['size'] as num?)?.toInt() ?? 0,
        created: j['created'] as String? ?? '',
        rawUrl: j['raw_url'] as String? ?? '',
      );
}

class JiraCard {
  final String key;
  final String title;
  final String status;
  final String jiraUrl;
  final String age;
  final String jobState;
  final String jobStatus;
  final String jobName;
  final int tokensInput;
  final int tokensOutput;
  final int tokensCacheRead;
  final double costUsd;
  final int aiLevel;
  final String aiPhase;
  final int runCount;

  JiraCard({
    required this.key,
    required this.title,
    required this.status,
    required this.jiraUrl,
    required this.age,
    required this.jobState,
    required this.jobStatus,
    required this.jobName,
    required this.tokensInput,
    required this.tokensOutput,
    required this.tokensCacheRead,
    required this.costUsd,
    required this.aiLevel,
    required this.aiPhase,
    required this.runCount,
  });

  factory JiraCard.fromJson(Map<String, dynamic> j) => JiraCard(
        key: j['key'] as String? ?? '',
        title: j['title'] as String? ?? '',
        status: j['status'] as String? ?? '',
        jiraUrl: j['jira_url'] as String? ?? '',
        age: j['age'] as String? ?? '',
        jobState: j['job_state'] as String? ?? '',
        jobStatus: j['job_status'] as String? ?? '',
        jobName: j['job_name'] as String? ?? '',
        tokensInput: (j['tokens_input'] as num?)?.toInt() ?? 0,
        tokensOutput: (j['tokens_output'] as num?)?.toInt() ?? 0,
        tokensCacheRead: (j['tokens_cache_read'] as num?)?.toInt() ?? 0,
        costUsd: (j['cost_usd'] as num?)?.toDouble() ?? 0.0,
        aiLevel: (j['ai_level'] as num?)?.toInt() ?? -1,
        aiPhase: j['ai_phase'] as String? ?? '',
        runCount: (j['run_count'] as num?)?.toInt() ?? 0,
      );
}

class ActiveAgentJob {
  final int id;
  final String role;
  final String jobName;
  final String? startedAt;
  ActiveAgentJob({required this.id, required this.role, required this.jobName, required this.startedAt});
  factory ActiveAgentJob.fromJson(Map<String, dynamic> j) => ActiveAgentJob(
        id: (j['id'] as num?)?.toInt() ?? 0,
        role: j['role'] as String? ?? '',
        jobName: j['job_name'] as String? ?? '',
        startedAt: j['started_at'] as String?,
      );
}

class PoQuestion {
  final String commentId;
  final String text;
  final String? created;
  PoQuestion({required this.commentId, required this.text, required this.created});
  factory PoQuestion.fromJson(Map<String, dynamic> j) => PoQuestion(
        commentId: j['comment_id'] as String? ?? '',
        text: j['text'] as String? ?? '',
        created: j['created'] as String?,
      );
}

class MainBuild {
  final String sha;
  final String shaAge;
  final String message;
  final String previewUrl;
  final List<Map<String, dynamic>> phases;
  final List<BuildRun> recentRuns;
  MainBuild({
    required this.sha,
    required this.shaAge,
    required this.message,
    required this.previewUrl,
    required this.phases,
    required this.recentRuns,
  });
  factory MainBuild.fromJson(Map<String, dynamic>? j) {
    if (j == null) {
      return MainBuild(
        sha: '', shaAge: '', message: '', previewUrl: '', phases: [],
        recentRuns: [],
      );
    }
    return MainBuild(
      sha: j['sha'] as String? ?? '',
      shaAge: j['sha_age'] as String? ?? '',
      message: j['message'] as String? ?? '',
      previewUrl: j['preview_url'] as String? ?? '',
      phases: (j['phases'] as List? ?? [])
          .map((p) => Map<String, dynamic>.from(p as Map))
          .toList(),
      recentRuns: (j['recent_runs'] as List? ?? [])
          .map((r) => BuildRun.fromJson(Map<String, dynamic>.from(r as Map)))
          .toList(),
    );
  }
}

class PrCard {
  final int number;
  final String title;
  final String htmlUrl;
  final String branch;
  final String author;
  final String updatedAge;
  final String previewUrl;
  final String jiraStatus;
  final String aiPhase;
  final List<Map<String, dynamic>> phases;
  final String lastCommitAge;
  final String headSha;

  PrCard({
    required this.number,
    required this.title,
    required this.htmlUrl,
    required this.branch,
    required this.author,
    required this.updatedAge,
    required this.previewUrl,
    required this.jiraStatus,
    required this.aiPhase,
    required this.phases,
    required this.lastCommitAge,
    required this.headSha,
  });

  factory PrCard.fromJson(Map<String, dynamic> j) => PrCard(
        number: (j['number'] as num?)?.toInt() ?? 0,
        title: j['title'] as String? ?? '',
        htmlUrl: j['html_url'] as String? ?? '',
        branch: j['branch'] as String? ?? '',
        author: j['author'] as String? ?? '',
        updatedAge: j['updated_age'] as String? ?? '',
        previewUrl: j['preview_url'] as String? ?? '',
        jiraStatus: j['jira_status'] as String? ?? '',
        aiPhase: j['ai_phase'] as String? ?? '',
        phases: (j['phases'] as List? ?? [])
            .map((p) => Map<String, dynamic>.from(p as Map))
            .toList(),
        lastCommitAge: j['last_commit_age'] as String? ?? '',
        headSha: j['head_sha'] as String? ?? '',
      );
}

class ClosedPr {
  final int number;
  final String title;
  final String htmlUrl;
  final String branch;
  final String mergedAge;
  final String mergedAt;
  final String headSha;
  final String storyKey;     // leeg = niet-AI-PR (ci-bumps etc.)
  final int tokensInput;
  final int tokensOutput;
  final int tokensCacheRead;
  final double costUsd;
  final int runCount;

  ClosedPr({
    required this.number,
    required this.title,
    required this.htmlUrl,
    required this.branch,
    required this.mergedAge,
    required this.mergedAt,
    required this.headSha,
    required this.storyKey,
    required this.tokensInput,
    required this.tokensOutput,
    required this.tokensCacheRead,
    required this.costUsd,
    required this.runCount,
  });

  factory ClosedPr.fromJson(Map<String, dynamic> j) => ClosedPr(
        number: (j['number'] as num?)?.toInt() ?? 0,
        title: j['title'] as String? ?? '',
        htmlUrl: j['html_url'] as String? ?? '',
        branch: j['branch'] as String? ?? '',
        mergedAge: j['merged_age'] as String? ?? '',
        mergedAt: j['merged_at'] as String? ?? '',
        headSha: j['head_sha'] as String? ?? '',
        storyKey: j['story_key'] as String? ?? '',
        tokensInput: (j['tokens_input'] as num?)?.toInt() ?? 0,
        tokensOutput: (j['tokens_output'] as num?)?.toInt() ?? 0,
        tokensCacheRead: (j['tokens_cache_read'] as num?)?.toInt() ?? 0,
        costUsd: (j['cost_usd'] as num?)?.toDouble() ?? 0.0,
        runCount: (j['run_count'] as num?)?.toInt() ?? 0,
      );
}

class HomeState {
  final String fetchedAt;
  final MainBuild main;
  final List<JiraCard> aiActive;
  final List<PrCard> openPrs;
  final List<ClosedPr> closedPrs;

  HomeState({
    required this.fetchedAt,
    required this.main,
    required this.aiActive,
    required this.openPrs,
    required this.closedPrs,
  });

  factory HomeState.fromJson(Map<String, dynamic> j) => HomeState(
        fetchedAt: j['fetched_at'] as String? ?? '',
        main: MainBuild.fromJson(j['main'] as Map<String, dynamic>?),
        aiActive: (j['ai_active'] as List? ?? [])
            .map((c) => JiraCard.fromJson(Map<String, dynamic>.from(c as Map)))
            .toList(),
        openPrs: (j['open_prs'] as List? ?? [])
            .map((c) => PrCard.fromJson(Map<String, dynamic>.from(c as Map)))
            .toList(),
        closedPrs: (j['closed_prs'] as List? ?? [])
            .map((c) => ClosedPr.fromJson(Map<String, dynamic>.from(c as Map)))
            .toList(),
      );
}
