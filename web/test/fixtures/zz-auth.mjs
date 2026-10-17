// লগইনের কোড পাঠানো (পরীক্ষায় শুধু rid ফেরত)
export default { '/api/auth/send-code': () => ({ ok: true, rid: '0123456789abcdef0123456789abcdef' }) };
