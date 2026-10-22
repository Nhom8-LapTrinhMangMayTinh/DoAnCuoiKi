| STT | Chức năng | Kết quả | Ghi chú |
| --- | --------- | ------- | ------- |
| 1 | Đăng ký 5 tài khoản | Pass |  |
| 2 | Đăng ký trùng username bị từ chối | Pass | Tên đăng nhập đã tồn tại |
| 3 | Đăng nhập sai mật khẩu bị từ chối | Pass |  |
| 4 | Đăng nhập 5 client đồng thời | Pass |  |
| 5 | Chặn đăng nhập 2 nơi cùng 1 tài khoản | Pass |  |
| 6 | Danh sách user + trạng thái Online | Pass | 6 user |
| 7 | Tạo phòng có mật khẩu | Pass |  |
| 8 | Tạo phòng trùng tên bị từ chối | Pass |  |
| 9 | Vào phòng sai mật khẩu bị từ chối | Pass |  |
| 10 | 5 người cùng vào phòng (đúng mật khẩu) | Pass |  |
| 11 | Danh sách thành viên + vai trò | Pass | MEMBER_LIST 1 alice63201,OWNER,1 bob63201,MEMBER,1 carol63201,MEMBER,1 dave63201,MEMBER,1 eve63201,MEMBER,1 |
| 12 | Chat nhóm realtime: 5 client đều nhận đúng nội dung | Pass |  |
| 13 | Nội dung truyền trên socket đã mã hóa AES | Pass |  |
| 14 | Chat riêng 1-1: đúng người nhận, người thứ 3 không nhận được | Pass |  |
| 15 | Member không được cấp quyền Admin | Pass | Chỉ chủ phòng mới được đổi quyền Admin |
| 16 | Chủ phòng cấp quyền Admin cho bob | Pass |  |
| 17 | Admin xóa thành viên khỏi phòng (KICK) | Pass |  |
| 18 | Người bị kick không gửi được tin vào phòng | Pass |  |
| 19 | Gửi liên tiếp 120 tin nhắn, client nhận đủ | Pass |  |
| 20 | Vào phòng tải 50 tin gần nhất (trang 0) | Pass | 50 tin, hasMore=true |
| 21 | Pagination: trang 1 tải thêm 50 tin cũ | Pass | 50 tin, hasMore=true |
| 22 | Trang cuối còn lại < 50 tin, hasMore=false; không trùng/thiếu tin, đúng thứ tự | Pass | trang2=21 tin, tổng=121 |
| 23 | Người không ở trong phòng không đọc được lịch sử | Pass |  |
| 24 | Gửi file 3MB vào phòng, thành viên nhận thông báo FILE | Pass | gc-small17960928059947721064.bin 3145851B |
| 25 | Tải file về: nội dung khớp SHA-256 | Pass |  |
| 26 | Gửi hình ảnh riêng 1-1 và tải về nguyên vẹn | Pass |  |
| 27 | Người ngoài cuộc không tải được file riêng | Pass |  |
| 28 | Gửi file lớn 20MB (server giới hạn RAM 128MB) không crash | Pass | 0.5s |
| 29 | Tải file lớn 20MB: SHA-256 khớp, có tiến trình | Pass | 0.5s |
| 30 | Server vẫn hoạt động sau khi truyền file lớn | Pass |  |
| 31 | Client tắt đột ngột -> Server vẫn chạy, các client khác vẫn chat | Pass |  |
| 32 | Trạng thái user bị mất kết nối chuyển Offline | Pass |  |
| 33 | Client rớt mạng đăng nhập lại được | Pass |  |
| 34 | Ngắt kết nối giữa chừng khi đang upload -> server dọn file dở, vẫn chạy | Pass |  |
