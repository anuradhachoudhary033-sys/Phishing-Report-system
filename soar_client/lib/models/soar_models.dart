/// Data models used across the three SOAR tiers.
library;

/// Represents a single phishing block event streamed from the
/// backend via WebSockets (Tier 1).
class PhishingEvent {
  final String id;
  final String url;
  final double riskScore;
  final bool isPhishing;
  final int bloomMatchCount;
  final String sourceIp;
  final String peerNode;
  final DateTime timestamp;

  const PhishingEvent({
    required this.id,
    required this.url,
    required this.riskScore,
    required this.isPhishing,
    required this.bloomMatchCount,
    required this.sourceIp,
    required this.peerNode,
    required this.timestamp,
  });

  factory PhishingEvent.fromJson(Map<String, dynamic> json) {
    return PhishingEvent(
      id: json['id'] as String? ?? '',
      url: json['url'] as String? ?? '',
      riskScore: (json['risk_score'] as num?)?.toDouble() ?? 0.0,
      isPhishing: json['is_phishing'] as bool? ?? false,
      bloomMatchCount: json['bloom_match_count'] as int? ?? 0,
      sourceIp: json['source_ip'] as String? ?? '0.0.0.0',
      peerNode: json['peer_node'] as String? ?? 'unknown',
      timestamp: json['timestamp'] != null
          ? DateTime.tryParse(json['timestamp'] as String) ?? DateTime.now()
          : DateTime.now(),
    );
  }
}

/// Tier 2 — sandbox analysis alert.
class SandboxAlert {
  final String id;
  final String payloadHash;
  final String verdict;        // 'malicious', 'suspicious', 'clean'
  final double confidenceScore;
  final DateTime detonatedAt;
  final String sandboxId;

  const SandboxAlert({
    required this.id,
    required this.payloadHash,
    required this.verdict,
    required this.confidenceScore,
    required this.detonatedAt,
    required this.sandboxId,
  });

  factory SandboxAlert.fromJson(Map<String, dynamic> json) {
    return SandboxAlert(
      id: json['id'] as String? ?? '',
      payloadHash: json['payload_hash'] as String? ?? '',
      verdict: json['verdict'] as String? ?? 'unknown',
      confidenceScore: (json['confidence_score'] as num?)?.toDouble() ?? 0.0,
      detonatedAt: json['detonated_at'] != null
          ? DateTime.tryParse(json['detonated_at'] as String) ?? DateTime.now()
          : DateTime.now(),
      sandboxId: json['sandbox_id'] as String? ?? '',
    );
  }

  bool get isMalicious => verdict == 'malicious';
  bool get isSuspicious => verdict == 'suspicious';
}

/// Tier 3 — intelligence report metadata.
class IntelReport {
  final String reportId;
  final String certInRefNo;
  final String status;          // 'generated', 'submitted', 'acknowledged'
  final DateTime generatedAt;
  final String downloadUrl;

  const IntelReport({
    required this.reportId,
    required this.certInRefNo,
    required this.status,
    required this.generatedAt,
    required this.downloadUrl,
  });

  factory IntelReport.fromJson(Map<String, dynamic> json) {
    return IntelReport(
      reportId: json['report_id'] as String? ?? '',
      certInRefNo: json['cert_in_ref_no'] as String? ?? '',
      status: json['status'] as String? ?? 'unknown',
      generatedAt: json['generated_at'] != null
          ? DateTime.tryParse(json['generated_at'] as String) ?? DateTime.now()
          : DateTime.now(),
      downloadUrl: json['download_url'] as String? ?? '',
    );
  }
}
