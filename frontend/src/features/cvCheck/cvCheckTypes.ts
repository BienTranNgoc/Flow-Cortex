export interface CvCheckDimensions {
  readonly hardSkills: number;
  readonly experienceImpact: number;
  readonly softSkills: number;
  readonly atsCompliance: number;
  readonly domainRelevance: number;
}

export interface CvCheckBullet {
  readonly original: string;
  readonly improved: string;
}

export interface CvJobProfile {
  readonly primaryRole: string;
  readonly seniority: string;
  readonly topSkills: readonly string[];
  readonly location: string;
}

export interface RecommendedJob {
  readonly title: string;
  readonly company: string;
  readonly location: string;
  readonly salary?: string;
  readonly workMode?: string;
  readonly matchScore: number;
  readonly matchedSkills: readonly string[];
  readonly summary: string;
  readonly source: string;
  readonly applyUrl: string;
}

export interface JobSearchLink {
  readonly label: string;
  readonly url: string;
}

export interface JobRecommendationsResponse {
  readonly jobs: readonly RecommendedJob[];
  readonly searchLinks: readonly JobSearchLink[];
  readonly lastSyncedAt?: string | null;
  readonly cached?: boolean;
  readonly configured?: boolean;
}

export interface CvRubricWeights {
  readonly experienceImpact: number;
  readonly hardSkills: number;
  readonly atsCompliance: number;
  readonly domainRelevance: number;
  readonly softSkills: number;
}

export interface CvRubricInfo {
  readonly id: string;
  readonly name: string;
  readonly tier: 'TIER_1' | 'TIER_2' | 'TIER_3' | 'TIER_4';
  readonly weights: CvRubricWeights;
  readonly capsApplied: readonly string[];
}

export interface CvAtsCheck {
  readonly id: string;
  readonly status: 'PASS' | 'WARN' | 'FAIL';
  readonly detail: string;
  readonly standard: string;
}

export interface CvCheckReport {
  readonly overallScore: number;
  readonly dimensions: CvCheckDimensions;
  readonly strengths: readonly string[];
  readonly weaknesses: readonly string[];
  readonly missingKeywords: readonly string[];
  readonly rewrittenBullets: readonly CvCheckBullet[];
  readonly interviewQuestions: readonly string[];
  readonly jobProfile?: CvJobProfile;
  readonly rubric?: CvRubricInfo;
  readonly atsChecks?: readonly CvAtsCheck[];
}

export const SAMPLE_CV = `HỌ VÀ TÊN: NGUYỄN VĂN AN
Vị trí: Backend Engineer (Java / Spring Boot)
Email: an.nguyen@example.com | Điện thoại: 0912 345 678 | GitHub: github.com/annguyen-dev

TỔNG QUAN NĂNG LỰC
Kỹ sư Backend với hơn 3 năm kinh nghiệm phát triển hệ thống phân tán, RESTful API và microservices bằng Java (Spring Boot), PostgreSQL, Redis và Docker. Có kinh nghiệm tối ưu hóa truy vấn CSDL và triển khai CI/CD trên AWS/GCP.

KỸ NĂNG CHUYÊN MÔN
- Ngôn ngữ: Java 17/21, SQL, Bash.
- Framework & Thư viện: Spring Boot 3, Spring Data JPA, Spring Security, Hibernate, JUnit 5, Mockito.
- Cơ sở dữ liệu: PostgreSQL, MySQL, Redis (caching).
- Công cụ & Nền tảng: Docker, Docker Compose, Git, Maven, Gradle, AWS (EC2, S3), GitHub Actions.
- Kiến trúc & Quy trình: Microservices, RESTful API, OOP, Clean Architecture, Agile/Scrum.

KINH NGHIỆM LÀM VIỆC
Công ty Cổ phần Công nghệ ABC (06/2022 - Hiện tại)
Vị trí: Kỹ sư Phần mềm Backend (Spring Boot)
- Tham gia phát triển hệ thống quản lý đơn hàng phục vụ 50.000 người dùng hoạt động hàng ngày (DAU).
- Thiết kế và triển khai 15+ RESTful API cho module thanh toán và tích hợp cổng thanh toán VNPay, MoMo.
- Viết unit test và integration test đạt độ bao phủ kiểm thử 75%, giảm tỷ lệ lỗi hồi quy trong các đợt phát hành.
- Sử dụng Redis cache để lưu dữ liệu danh mục sản phẩm, giảm tải cho PostgreSQL và cải thiện độ trễ phản hồi API từ 450ms xuống 120ms.
- Phối hợp với đội Frontend và DevOps để tự động hóa quy trình build và deploy qua GitHub Actions.

Công ty Giải pháp Phần mềm XYZ (03/2021 - 05/2022)
Vị trí: Lập trình viên Java Junior
- Xây dựng các chức năng CRUD và báo cáo thống kê bằng Spring Boot và MySQL cho phần mềm ERP nội bộ.
- Tối ưu hóa các câu truy vấn SQL chậm và tạo chỉ mục (index), giúp rút ngắn thời gian xuất báo cáo tháng.
- Sửa lỗi và bảo trì hệ thống định kỳ theo yêu cầu của khách hàng.

HỌC VẤN & CHỨNG CHỈ
- Cử nhân Công nghệ Thông tin - Đại học Bách Khoa (2017 - 2021) - Tốt nghiệp loại Giỏi.
- Chứng chỉ AWS Certified Cloud Practitioner (2023).
- Tiếng Anh: TOEIC 780 (Đọc hiểu tài liệu kỹ thuật tốt, giao tiếp làm việc cơ bản).`;

export const SAMPLE_JD = `VỊ TRÍ TUYỂN DỤNG: SENIOR / MIDDLE JAVA BACKEND DEVELOPER
Công ty: FinTech Global Solutions
Địa điểm: Hà Nội / Hybrid

MÔ TẢ CÔNG VIỆC:
- Thiết kế, phát triển và tối ưu hóa các dịch vụ backend hiệu năng cao cho nền tảng thanh toán và ví điện tử.
- Xây dựng kiến trúc Microservices có khả năng chịu tải lớn (High Throughput & Low Latency), xử lý hàng triệu giao dịch mỗi ngày.
- Tích hợp hệ thống với các ngân hàng, cổng thanh toán quốc tế và hệ thống kiểm soát gian lận (Fraud Detection).
- Thiết kế cấu trúc cơ sở dữ liệu quan hệ (PostgreSQL) và NoSQL, xây dựng cơ chế caching đa tầng (Redis, Memcached).
- Áp dụng Message Broker (Kafka / RabbitMQ) để xử lý các tác vụ bất đồng bộ và sự kiện phân tán (Event-Driven Architecture).
- Tham gia đánh giá mã nguồn (Code Review), viết Unit/Integration Tests và hướng dẫn (mentor) các thành viên ít kinh nghiệm hơn.

YÊU CẦU ỨNG VIÊN:
- Tối thiểu 3+ năm kinh nghiệm lập trình Java với Spring Boot, Spring Cloud, Hibernate/JPA.
- Hiểu biết sâu sắc về OOP, Design Patterns, Clean Architecture và REST API principles.
- Thành thạo PostgreSQL/MySQL, hiểu rõ về tối ưu hóa câu truy vấn, indexing, transaction isolation và locking.
- Có kinh nghiệm thực chiến với Redis Caching và Message Queue (Kafka hoặc RabbitMQ).
- Kinh nghiệm làm việc với Docker, Kubernetes, CI/CD pipeline và đám mây (AWS / GCP).
- Tư duy logic tốt, tinh thần trách nhiệm cao, khả năng giải quyết vấn đề và kỹ năng làm việc nhóm tốt.
- Ưu tiên ứng viên có kinh nghiệm trong lĩnh vực FinTech, E-commerce, Banking hoặc hệ thống xử lý giao dịch tài chính.`;

export function createSamplePdfBlob(): Blob {
  const content = `%PDF-1.4
1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >> endobj
4 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj
5 0 obj << /Length 260 >> stream
BT
/F1 16 Tf
50 720 Td
(NGUYEN VAN AN - JAVA BACKEND ENGINEER) Tj
/F1 11 Tf
0 -30 Td
(Email: an.nguyen@example.com | Phone: 0912 345 678) Tj
0 -25 Td
(SUMMARY: Backend engineer with 3+ years experience in Spring Boot, PostgreSQL, Redis) Tj
0 -25 Td
(EXPERIENCE:) Tj
0 -20 Td
(- Software Engineer at ABC Tech: Built RESTful APIs, optimized Redis caching) Tj
0 -20 Td
(- Reduced API response latency from 450ms to 120ms) Tj
0 -20 Td
(- Designed order management microservices for 50,000 DAU) Tj
0 -25 Td
(SKILLS: Java 17/21, Spring Boot 3, PostgreSQL, Redis, Docker, Microservices, Git) Tj
ET
endstream endobj
xref
0 6
0000000000 65535 f 
0000000009 00000 n 
0000000058 00000 n 
0000000115 00000 n 
0000000244 00000 n 
0000000318 00000 n 
trailer << /Size 6 /Root 1 0 R >>
startxref
630
%%EOF`;
  return new Blob([content], { type: 'application/pdf' });
}
