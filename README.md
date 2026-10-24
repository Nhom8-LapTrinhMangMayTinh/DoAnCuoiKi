# ChatApp — Ứng dụng Chat Nhóm với Quản lý Phòng và Lịch sử Tin nhắn

## 1. Giới thiệu dự án

ChatApp là ứng dụng trò chuyện nhóm được xây dựng bằng Java, sử dụng mô hình Client–Server và TCP Socket để trao đổi dữ liệu qua mạng. Ứng dụng hướng đến việc hỗ trợ người dùng giao tiếp, quản lý phòng chat, lưu trữ lịch sử tin nhắn và sử dụng các chức năng cộng tác trực tuyến.

Dự án được phát triển theo từng module, bao gồm xử lý kết nối mạng, quản lý phòng và người dùng, cơ sở dữ liệu, bảo mật, truyền tệp, tin nhắn thoại, gọi video và giao diện người dùng.

## 2. Công nghệ sử dụng

- **Ngôn ngữ lập trình:** Java.
- **Giao diện:** Java Swing.
- **Giao tiếp mạng:** TCP Socket.
- **Xử lý đồng thời:** Multithreading.
- **Cơ sở dữ liệu:** SQLite.
- **Bảo mật:** AES, SHA-256 và các tiện ích kiểm tra mật khẩu.
- **Quản lý mã nguồn:** Git và GitHub.

## 3. Các chức năng chính

- Đăng ký, đăng nhập và quản lý tài khoản người dùng.
- Tạo phòng, tham gia phòng, quản lý thành viên và giải tán phòng.
- Theo dõi trạng thái trực tuyến của người dùng.
- Gửi, nhận, tìm kiếm và quản lý tin nhắn.
- Lưu trữ và xuất lịch sử hội thoại.
- Tương tác với tin nhắn thông qua các chức năng sửa, thu hồi, ghim và cảm xúc.
- Ghi âm, gửi và phát tin nhắn thoại.
- Truyền tệp giữa các người dùng.
- Hỗ trợ gọi video cá nhân và gọi video nhóm.
- Quản lý dữ liệu bằng SQLite.
- Cung cấp các tiện ích bảo mật và giao thức trao đổi dữ liệu giữa Client và Server.
- Hỗ trợ các chức năng quản trị hệ thống.

*Các chức năng được mô tả theo phạm vi phát triển của nhóm; trạng thái hoàn thiện cần căn cứ vào mã nguồn và kết quả kiểm thử thực tế.*

## 4. Cấu trúc module

Module | Vai trò

com.chatapp.protocol | Định nghĩa giao thức trao đổi dữ liệu giữa Client và Server.

com.chatapp.security | Cung cấp các tiện ích liên quan đến mã hóa và xử lý bảo mật.

com.chatapp.server | Xử lý các dịch vụ phía Server, bao gồm truyền tệp và gọi video.

com.chatapp.client | Xử lý giao diện và các chức năng phía Client.

com.chatapp.database | Quản lý việc lưu trữ và truy xuất dữ liệu.

## 5. Ma trận đóng góp của các thành viên

Bảng dưới đây tổng hợp phạm vi công việc của từng thành viên để phục vụ việc đối chiếu khi đánh giá đồ án.

| MSSV | Họ và tên | Công việc đã thực hiện | Merge Requests |
|---|---|---|---|
| Chưa cập nhật | Huy | Server, xử lý đa luồng, quản lý phòng và tài khoản, trạng thái người dùng, tin nhắn thoại. | Cập nhật số PR đã merge |
| 2380602410 | Võ Đinh Quốc Trường | Giao thức ứng dụng, bảo mật, truyền tệp và gọi video 1-1/nhóm ở phía Client và Server. | Cập nhật số PR đã merge |
| Chưa cập nhật | Nam | Cơ sở dữ liệu, xử lý tin nhắn, lịch sử, tìm kiếm, cảm xúc, trạng thái đã đọc và hồ sơ người dùng. | Cập nhật số PR đã merge |
| Chưa cập nhật | Trung | Giao diện chat chính, đăng nhập, hội thoại, Dashboard quản trị và các controller phía Client. | Cập nhật số PR đã merge |
| Chưa cập nhật | Hoàng | Thành phần giao diện phòng chat, người dùng, hồ sơ, emoji và các công việc kiểm thử được phân công. | Cập nhật số PR đã merge |


## 6. Quy trình quản lý mã nguồn

Dự án sử dụng Git và GitHub để quản lý phiên bản, theo dõi tiến độ và kiểm tra thay đổi.

- Các chức năng được phát triển theo từng module và phạm vi công việc.
- Sử dụng commit message theo quy ước Conventional Commits, ví dụ `feat:`, `fix:` và `docs:`.
- Sử dụng Pull Request để đề xuất và kiểm tra các thay đổi trước khi hợp nhất.
- Thực hiện review mã nguồn theo quy định của nhóm.
- Hạn chế đưa tệp build, dữ liệu cá nhân, thông tin đăng nhập và tệp cấu hình nhạy cảm vào repository.

## 7. Định hướng phát triển

- Hoàn thiện và đồng bộ các module Client, Server và Database.
- Kiểm thử giao tiếp đồng thời giữa nhiều người dùng.
- Hoàn thiện và kiểm thử các chức năng truyền tệp, tin nhắn thoại và gọi video.
- Cải thiện giao diện và trải nghiệm người dùng.
- Tăng cường kiểm thử bảo mật, tính ổn định và khả năng xử lý lỗi.
