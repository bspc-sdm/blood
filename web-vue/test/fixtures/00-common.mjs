// সবার কাজে লাগে এমন উত্তর (me, ক্যাটালগ, খালি তালিকা)
export const ME = { badge: null, isAdmin: false, isOwner: false, phoneCheck: false, phoneVerified: false, unread: 2 };
export default {
  me: () => ME,
  ambulance_mine: () => ({ items: [] }),
  pharmacy_me: () => ({ pharmacy: null }),
  bloodbank_mine: () => ({ items: [] }),
  lab_me: () => ({ lab: null }),
  device_register: () => ({ ok: true }),
};
