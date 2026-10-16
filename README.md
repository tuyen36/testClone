# Sub TTS — ban rieng chay song song app Netflix goc

Bo WebView + offset. 3 tab: **Xem** (the cau + dieu khien + cai dat) /
**Tim sub** (file + online + sao luu) / **Da luu**.
Giao dien do Netflix: nut to chu thuong bo goc, tab duoi kieu mau,
tab dang chon nen do.
Nut noi gon: cau + gio + Play/⏮/⏭ + nut ⋯ mo them.

## Cach dung

1. Chon file `.srt` → Play.
2. Thoat sang **app Netflix goc** xem phim.
3. Dieu khien nhanh bang **nut noi mo 30%** (Play/Pause, -10s/+10s,
   gio chay `mm:ss`, volume sub, chon giong, tach/gop loa).
4. Muon nhay toi doan khac: keo **thanh timeline** (trong app hoac tren
   nut noi) roi tha tay — sub phat tiep tu moc do. Hoac bam
   **⏮ Cau truoc / Cau sau ⏭** de nhay theo cau phu de.
5. **Can gio dem nguoc voi Netflix** (Netflix chi bao gio con lai):
   nhap **tong thoi luong phim** 1 lan (vd `1:32:00`) → app hien
   **gio con lai** (`-25:30`) giong het Netflix de so truc tiep.
   Muon nhay nhanh: bam **Nhap gio con lai** → go con so dang thay
   tren Netflix (vd `25:30`) → sub nhay toi dung cho, tinh chinh
   them bang `-10s/+10s`.

## Tim sub online (SubDL / OpenSubtitles / SubSource)

1. Bam **Key sub** → nhap key cac nguon (luu tren may, khong gui di dau):
   SubDL key (`subdl.com/panel/api`), SubSource key (trang profile),
   OpenSubtitles key + tai khoan (user/pass de tai). Tick bat/tat nguon.
2. Bam **Tim sub online** → go ten phim, chon Phim le/bo (+season/tap),
   tick nguon → **Tim**.
3. List ket qua uu tien **sub Viet** kem ten ban release → chon **dung
   ban** khop phim Netflix dang xem → app tu tai, bung zip, nap sub.
   Ket qua SubSource/OpenSubtitles co **anh bia phim**, SubDL hien chu.
   Moi dong hien **ten phim • nam • loai** (Phim le/bo) + ten ban release.
4. Ten phim Netflix hien tieng Viet: bam **VI→EN** canh o ten phim →
   go ten Viet → chon phim (co poster + ten Anh + nam) → app tu dien
   ten tieng Anh vao o tim sub. Can key TMDB mien phi
   (`themoviedb.org` → Settings → API), nhap trong **Key sub**.

## Sao luu / khoi phuc

- Nut **Sao luu**: chon cho luu file `subtts-backup.json` (gom toc do,
  volume, giong, tach loa, tong phim + key sub online). Giu file can than.
- Nut **Khoi phuc**: chon file backup → app nap lai toan bo, khoi nhap lại.

## Giong doc

- **Google (offline)**: khong can mang. Bam **Giong Google** de chon
  trong cac giong Viet co tren may (neu may chi co 1 giong thi khong
  co gi de chon). Chon nho theo backup.
- **HoaiMy (online)**: can mang, preload 35 cau, cache mp3.
- **NamMinh (online)**: giong nam, cung cach HoaiMy (doi tren nut noi
  theo vong Google → HoaiMy → NamMinh → Vieneu).
- **Vieneu Turbo tren PC nha (online LAN)**: can PC bat + server Vieneu
  (`apps/openai_speech.py`) + chung WiFi. Bam **Vieneu (PC)** → tai danh
  sach giong tu server (25 preset + giong clone nhu "Clone") → chon.
  Bam **Server Vieneu (PC)** de nhap dia chi server
  (vd `http://192.168.1.10:8000`) + API key (neu co). Preload 10 cau,
  cache wav rieng. Khong can mang Internet, chi can LAN nha.
  Nut **Xoa cache (N MB)** de don.

## Nho phim + hen gio + tu dung khi co cuoc goi

- Moi phim (theo ten file sub) tu nho **tong gio + vi tri dung**: nap lai
  sub cu → app hoi **xem tiep tu mm:ss** (tong gio khoi phai nhap lai).
- Nut **Luu sub vao may**: ghi sub dang doc thanh `.srt` trong bo nho
  rieng (khong can quyen). Lan sau mo app tu nap + hoi xem tiep.
  Xem het phim tu xoa file + ho so cho khoi day may.
- Tab **Da luu**: list sub da luu (moi nhat truoc, kem so cau + ngay luu).
  Cham de nap, giu de xoa 1 file, nut **Xoa tat ca** de don sach.
- Nut **Hen gio tat**: Tat / 15 / 30 / 60 / 90 phut, het gio tu dung doc
  (pause thi dem tiep khi Play, nap sub moi thi huy hen).
- Cuoc goi den: he thong tu tat tieng media — nghe xong bam Play tiep
  (lech gio thi nhay lai bang nut nhap gio con lai).

## Build

Nhap repo `tuyen36/web-tts-netflix` → Actions xanh → Artifacts `app-debug`.
Cai song song duoc voi ban Web TTS (khac applicationId).
