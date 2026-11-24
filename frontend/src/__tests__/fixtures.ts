export const me = { id: 1, name: "Datta Kulkarni", role: "OWNER", tenantName: "Shree Datta Electricals" };
export const dashboard = {
  kpis: { salesToday: 223000, ordersToday: 5, salesSameDayLastWeek: 448000, openOrders: 3, openOrderValue: 76259, receivables: 1307000,
    overdue30: 519000, collectedMtd: 3870000, salesMtd: 7724000, newMessages: 4, tallyPending: 1 },
  lowStock: [{ sku: "RC40", name: "RCCB", free: 16, reorderLevel: 30 }],
  dailySales: Array.from({ length: 26 }, (_, i) => ({ day: `2026-10-${String(i + 1).padStart(2, "0")}`, value: 300000 + i * 1000 })),
  sources: [{ source: "WHATSAPP", value: 58 }, { source: "SALESPERSON", value: 31 }, { source: "PHONE", value: 11 }],
  aging: [{ label: "0-30 d", value: 790000, bills: 9 }, { label: "31-60 d", value: 260000, bills: 4 }, { label: "61-90 d", value: 240000, bills: 2 }, { label: "90+ d", value: 24600, bills: 1 }],
  creditRisk: [{ id: 5, name: "Shiv Shakti Traders", limit: 100000, exposure: 92000 }],
  salespeople: [{ id: 3, name: "Amit Jadhav", target: 2400000, achieved: 1850000 }],
  activity: [{ at: "2026-11-24T05:30:00Z", message: "SO-1036 confirmed" }],
};
export const inbox = [
  { id: 1, phone: "919822041001", status: "NEW", customer: "Mahalaxmi Electricals", preview: "1.5 wire - 20 coil", lastMessageAt: "2026-11-24T05:12:00Z" },
  { id: 2, phone: "919011054005", status: "NEW", customer: "Shiv Shakti Traders", preview: "2 fan 1200mm", lastMessageAt: "2026-11-24T05:21:00Z" },
];
export const thread1 = { ...inbox[0], customerId: 1, town: "Hadapsar", tier: "Tier A", messages: [{ id: 10, direction: "IN", body: "1.5 wire - 20 coil\nchangeover 63A x 2", kind: "TEXT", createdAt: "2026-11-24T05:12:00Z" }] };
export const draft1 = {
  lines: [
    { raw: "1.5 wire - 20 coil", qty: 20, productId: 1, sku: "W15", name: "FR House Wire 1.5 sq mm", rate: 1334, gstRate: 18, priceSource: "Tier A -8%", free: 312 },
    { raw: "changeover 63A x 2", qty: 2 },
  ],
  credit: { limit: 500000, outstanding: 269100, openOrders: 0, oldestDays: 41 },
};
export const thread2 = { ...inbox[1], customerId: 5, town: "Chakan", tier: "Tier C", messages: [{ id: 11, direction: "IN", body: "2 fan 1200mm", kind: "TEXT", createdAt: "2026-11-24T05:21:00Z" }] };
export const draft2 = {
  lines: [{ raw: "5 fan 1200mm", qty: 5, productId: 11, sku: "F12", name: "Ceiling Fan 1200 mm", rate: 1890, gstRate: 18, priceSource: "List price", free: 95 }],
  credit: { limit: 100000, outstanding: 92000, openOrders: 0, oldestDays: 96 },
};
export const inventory = [
  { id: 1, sku: "W15", name: "FR House Wire 1.5 sq mm", unit: "coil", category: "Wires", listRate: 1450, reorderLevel: 80, onHand: 320, reserved: 8, free: 312 },
  { id: 7, sku: "RC40", name: "RCCB 40A 4P 30mA", unit: "pc", category: "Switchgear", listRate: 2650, reorderLevel: 30, onHand: 22, reserved: 6, free: 16 },
];
export const orders = [
  { id: 30, number: 1035, status: "CONFIRMED", source: "SALESPERSON", total: 13121.6, createdAt: "2026-11-24T03:00:00Z", customer: "Ganesh Hardware", summary: "6x SW6", backorder: 0 },
  { id: 31, number: 1034, status: "PACKED", source: "WHATSAPP", total: 29601.48, createdAt: "2026-11-24T03:00:00Z", customer: "Patil Electricals", summary: "8x W15", backorder: 0 },
];
export const vehicles = [{ id: 1, regNo: "MH12 AB 4521", type: "Tata Ace", driver: "Raju Pawar" }];
export const summary = [{ status: "CONFIRMED", count: 1, value: 13121 }, { status: "PACKED", count: 1, value: 29601 }, { status: "DISPATCHED", count: 0, value: 0 }, { status: "DELIVERED", count: 0, value: 0 }];
export const customers = [{ id: 5, name: "Shiv Shakti Traders", town: "Chakan", phone: "919011054005", creditLimit: 100000, tierCode: "C", tierName: "Tier C", tierDiscount: 0, outstanding: 92000, oldestDays: 96, openOrders: 0, exposure: 92000 }];
export const tiers = [{ code: "A", name: "Tier A", discount: 8 }, { code: "C", name: "Tier C", discount: 0 }];
export const aging = [{ customerId: 5, customer: "Shiv Shakti Traders", d0: 44000, d30: 0, d60: 23400, d90: 24600, total: 92000, oldestDays: 96 }];
export const tally = { mode: "mock", autoSync: false, mapping: { salesLedger: "Sales", cgstLedger: "CGST", sgstLedger: "SGST", bankLedger: "HDFC" },
  queue: [{ id: 1, type: "SALES", ref: "INV-2304", party: "Mahalaxmi Electricals", amount: 58200 }], failed: [], log: [], postedToday: 0 };
export const field = { salespeople: [{ id: 3, name: "Amit Jadhav", beat: "Hadapsar", target: 2400000, visitsToday: 2, salesMtd: 1850000, collectedMtd: 1240000, lastCheckIn: "Mahalaxmi · 10:48" }],
  deliveries: [{ id: 40, number: 1033, status: "DISPATCHED", total: 42000, customer: "New India Lighting", town: "Camp", vehicle: "MH12 AB 4521", vehicleType: "Tata Ace", driver: "Raju Pawar", invoice: "INV-2305" }] };
export const godowns = [{ id: 1, code: "MAIN", name: "Hadapsar godown", isDefault: true }, { id: 2, code: "CHK", name: "Chakan branch", isDefault: false }];
export const rc40Detail = { id: 7, sku: "RC40", name: "RCCB 40A 4P 30mA", batchTracked: false,
  godowns: [{ godownId: 1, godown: "Hadapsar godown", onHand: 22, reserved: 6 }, { godownId: 2, godown: "Chakan branch", onHand: 10, reserved: 0 }], batches: [] };
export const pharmaItem = { id: 21, sku: "AMX500", name: "Amoxicillin 500 mg", unit: "strip", category: "Antibiotic", listRate: 92, reorderLevel: 200,
  onHand: 580, reserved: 0, free: 580, gstRate: 12, batchTracked: true, requiresLicence: true, nextExpiry: "2027-02-01", expired: 0 };
export const pharmaDetail = { id: 21, sku: "AMX500", name: "Amoxicillin 500 mg", batchTracked: true, godowns: [{ godownId: 1, godown: "Main store", onHand: 580, reserved: 0 }],
  batches: [{ id: 301, batchNo: "AM25C02", expiryDate: "2027-02-01", onHand: 180, reserved: 0, godown: "Main store", godownId: 1, expired: false, daysLeft: 75 }] };
export const suggestions = [{ productId: 7, sku: "RC40", name: "RCCB 40A 4P 30mA", reorderLevel: 30, free: 16, onOrder: 0, suggestedQty: 44, lastSupplierId: 2, lastRate: 2100, listRate: 2650 }];
export const suppliers = [{ id: 2, name: "Sure Switchgear Distributors", phone: "919822022200", leadDays: 2, openOrders: 1 }];
export const poDetail = { id: 9, number: 301, status: "SENT", total: 109032, supplier: "Medisupply C&F", godown: "Cold room", late: false, subtotal: 97350, tax: 11682,
  lines: [{ id: 91, productId: 24, sku: "INS30", name: "Insulin 30/70", batchTracked: true, qty: 50, receivedQty: 0, rate: 118 }], receipts: [] };
