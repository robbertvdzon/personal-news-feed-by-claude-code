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
    );
  }
}

class HandoverData {
  final String storyKey;
  final String jiraTitle;
  final AgentRun? refiner;
  final AgentRun? developer;
  final AgentRun? reviewer;
  final AgentRun? tester;

  HandoverData({
    required this.storyKey,
    required this.jiraTitle,
    required this.refiner,
    required this.developer,
    required this.reviewer,
    required this.tester,
  });

  factory HandoverData.fromJson(Map<String, dynamic> j) {
    AgentRun? agent(String key) {
      final raw = j[key];
      if (raw == null) return null;
      return AgentRun.fromJson(Map<String, dynamic>.from(raw as Map));
    }

    return HandoverData(
      storyKey: j['story_key'] as String? ?? '',
      jiraTitle: j['jira_title'] as String? ?? '',
      refiner: agent('refiner'),
      developer: agent('developer'),
      reviewer: agent('reviewer'),
      tester: agent('tester'),
    );
  }
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
  final String previewUrl;
  final List<Map<String, dynamic>> phases;
  MainBuild({required this.sha, required this.shaAge, required this.previewUrl, required this.phases});
  factory MainBuild.fromJson(Map<String, dynamic>? j) {
    if (j == null) return MainBuild(sha: '', shaAge: '', previewUrl: '', phases: []);
    return MainBuild(
      sha: j['sha'] as String? ?? '',
      shaAge: j['sha_age'] as String? ?? '',
      previewUrl: j['preview_url'] as String? ?? '',
      phases: (j['phases'] as List? ?? [])
          .map((p) => Map<String, dynamic>.from(p as Map))
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
