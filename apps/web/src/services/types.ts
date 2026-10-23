export interface User {
  userId: string;
  username: string;
  displayName: string;
  departmentId: string;
  permissions: string[];
  roles: string[];
  scopes: any[];
  bookingLimits?: { reservationMaxHours: number; applicationMaxHours: number };
}
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}
export interface RecordData {
  id: string;
  version: number;
  status?: string;
  allowedActions?: string[];
  [key: string]: any;
}
export interface Room extends RecordData {
  name: string;
  code: string;
  buildingName: string;
  campusName: string;
  seatCount: number;
  enabled: boolean;
}
export interface Seat extends RecordData {
  seatId: string;
  roomId: string;
  code: string;
  roomName: string;
  buildingName: string;
  campusName: string;
  hasPower: boolean;
  available: boolean;
}
