// কেয়ারগিভার ও পরিবারের তথ্যের ধরন
/** sharePsych: রোগী এই কেয়ারগিভারকে সাইকোলজিস্টের অ্যাপয়েন্টমেন্ট/নোট দেখাতে দিয়েছেন (practice; পুরোনো সার্ভারে নেই) */
export interface CareLink { patientUid: string; caretakerUid: string; patientName: string; caretakerName: string; caretakerEmail: string; status: string; createdAt: number; sharePsych?: boolean }
export interface FamilyMember { id: string; name: string; relation: string; age: number | null; gender: string; phone: string }
export interface CareData { caretakers: CareLink[]; caringFor: CareLink[]; family: FamilyMember[] }
