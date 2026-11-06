import { assistantCopy } from './assistantCopy';
import { requestsCopy } from './requestsCopy';

/** Every visible string of the Help Center widget, all tabs included. */
export const supportCopy = {
  launcher: {
    label: 'Trợ giúp',
    unread: 'Có phản hồi mới',
  },
  panel: {
    title: 'Trung tâm trợ giúp',
    close: 'Đóng',
  },
  tabs: {
    home: 'Trang chủ',
    assistant: 'Trợ lý',
    guides: 'Hướng dẫn',
    requests: 'Yêu cầu',
  },
  common: {
    loading: 'Đang tải…',
    retry: 'Thử lại',
    loadFailed: 'Không tải được nội dung. Bạn thử lại nhé.',
    back: 'Quay lại',
  },
  contact: {
    lead: 'Cần người hỗ trợ?',
    sendTo: (email: string) => `Gửi email tới ${email}`,
  },
  home: {
    greeting: 'Xin chào! Nodi có thể giúp gì cho bạn?',
    startHeading: 'Bắt đầu',
    suggestedHeading: 'Câu hỏi gợi ý',
    suggestedQuestions: [
      'Vì sao tôi bị báo thiếu credit?',
      'Làm sao chạy workflow đầu tiên?',
      'Vì sao lượt chạy của tôi bị lỗi?',
    ],
    newRequest: 'Gửi yêu cầu hỗ trợ',
  },
  article: {
    notPublished: 'Bài viết này không còn được công bố',
    backToGuides: 'Về Hướng dẫn',
    backToHome: 'Về Trang chủ',
  },
  guides: {
    searchLabel: 'Tìm bài hướng dẫn',
    searchPlaceholder: 'Ví dụ: thiếu credit',
    resultsHeading: 'Kết quả tìm kiếm',
    empty: 'Chưa có bài hướng dẫn nào',
    noMatch: 'Không tìm thấy bài phù hợp',
    askAssistant: 'Hỏi trợ lý',
  },
  assistant: assistantCopy,
  requests: requestsCopy,
  /** Messages for the `code` of a support problem response. */
  problems: {
    'support-disabled': 'Trung tâm trợ giúp tạm thời không hoạt động',
    'article-not-found': 'Bài viết này không còn được công bố',
    'assistant-quota': (resumeAt: string) =>
      `Bạn đã dùng hết lượt hỏi hôm nay. Bạn có thể hỏi tiếp sau ${resumeAt}.`,
    'assistant-paused': 'Trợ lý tạm thời không hoạt động',
    'assistant-disabled': 'Trợ lý tạm thời không hoạt động',
    'assistant-unavailable': 'Trợ lý đang bận, bạn thử lại sau ít phút nhé',
    'question-too-long': 'Câu hỏi tối đa 1000 ký tự',
    'attachment-rejected': 'Tệp không phải ảnh hợp lệ',
    'attachments-unavailable': 'Tạm thời chưa gửi được ảnh — bạn có thể gửi yêu cầu không kèm ảnh',
    'request-not-found': 'Không tìm thấy yêu cầu',
    'request-closed': 'Yêu cầu đã đóng',
    'transition-not-allowed': 'Yêu cầu không thể chuyển sang trạng thái này',
    'request-state-changed': 'Yêu cầu vừa được cập nhật — tải lại để xem',
    'idempotency-key-reused': 'Yêu cầu này đã được gửi — tải lại để xem',
  },
} as const;
