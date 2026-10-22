declare namespace CoreAPI {
  type Application = {
    id: string;
    version: number;
    status:
      | "PENDING"
      | "APPROVED"
      | "REJECTED"
      | "CANCELLED"
      | "EXPIRED"
      | "COMPLETED";
    currentStep?: 1 | 2;
    steps?: Record<string, any>[];
    actions?: Record<string, any>[];
    allowedActions: ("cancel" | "approve" | "reject")[];
  };

  type ApplicationCreate = {
    roomId: string;
    startAt: string;
    endAt: string;
    purposeTitle: string;
    purposeDescription: string;
  };

  type BookingLimits = {
    reservationMaxHours: number;
    applicationMaxHours: number;
  };

  type CurrentUser = {
    id: string;
    userId: string;
    username: string;
    displayName: string;
    email?: string;
    schoolNo?: string;
    departmentId?: string;
    enabled: boolean;
    authVersion?: number;
    version?: number;
    permissions: string[];
    roles: string[];
    scopes: RoleScope[];
    bookingLimits: BookingLimits;
  };

  type CurrentUserEnvelope = {
    data: CurrentUser;
    requestId: string;
  };

  type deleteApiV1RolesIdParams = {
    id: string;
  };

  type Envelope = {
    data: Record<string, any>;
    requestId: string;
  };

  type Error = {
    error: { code: string; message: string; details?: Record<string, any> };
    requestId: string;
  };

  type getApiV1AuditEventsParams = {
    page?: number;
    size?: number;
    action?: string;
    objectId?: string;
    roomId?: string;
    from?: string;
    to?: string;
  };

  type getApiV1AuthCsrfParams = {
    page?: number;
    size?: number;
  };

  type getApiV1AvailabilityRoomsParams = {
    page?: number;
    size?: number;
    startAt: string;
    endAt: string;
  };

  type getApiV1AvailabilitySeatsParams = {
    page?: number;
    size?: number;
    startAt: string;
    endAt: string;
  };

  type getApiV1BuildingsParams = {
    page?: number;
    size?: number;
  };

  type getApiV1CampusesParams = {
    page?: number;
    size?: number;
  };

  type getApiV1DepartmentsParams = {
    page?: number;
    size?: number;
  };

  type getApiV1NotificationDeliveriesParams = {
    page?: number;
    size?: number;
  };

  type getApiV1NotificationsParams = {
    page?: number;
    size?: number;
  };

  type getApiV1PermissionsParams = {
    page?: number;
    size?: number;
  };

  type getApiV1ReservationsIdParams = {
    id: string;
    page?: number;
    size?: number;
  };

  type getApiV1ReservationsParams = {
    page?: number;
    size?: number;
  };

  type getApiV1RolesParams = {
    page?: number;
    size?: number;
  };

  type getApiV1RoomApplicationsIdParams = {
    id: string;
    page?: number;
    size?: number;
  };

  type getApiV1RoomApplicationsParams = {
    page?: number;
    size?: number;
  };

  type getApiV1RoomsIdAvailabilityParams = {
    id: string;
    page?: number;
    size?: number;
    startAt: string;
    endAt: string;
  };

  type getApiV1RoomsIdDisplayCodeParams = {
    id: string;
    page?: number;
    size?: number;
  };

  type getApiV1RoomsIdResourceClaimsParams = {
    id: string;
    page?: number;
    size?: number;
    startAt?: string;
    endAt?: string;
    sourceType?: "M1" | "M2" | "M3";
  };

  type getApiV1RoomsIdSeatsParams = {
    id: string;
    page?: number;
    size?: number;
  };

  type getApiV1RoomsParams = {
    page?: number;
    size?: number;
  };

  type getApiV1StatisticsUsageParams = {
    page?: number;
    size?: number;
  };

  type getApiV1SystemConfigParams = {
    page?: number;
    size?: number;
  };

  type getApiV1UsersParams = {
    page?: number;
    size?: number;
    /** Case-insensitive username or displayName contains search; wildcard characters are treated literally. */
    q?: string;
  };

  type getApiV1VersionParams = {
    page?: number;
    size?: number;
  };

  type getApiV1ViolationsParams = {
    page?: number;
    size?: number;
  };

  type getRoomParams = {
    id: string;
  };

  type Login = {
    username: string;
    password: string;
  };

  type patchApiV1BuildingsIdParams = {
    id: string;
  };

  type patchApiV1CampusesIdParams = {
    id: string;
  };

  type patchApiV1DepartmentsIdParams = {
    id: string;
  };

  type patchApiV1RoomsIdParams = {
    id: string;
  };

  type patchApiV1SeatsIdParams = {
    id: string;
  };

  type patchApiV1UsersIdParams = {
    id: string;
  };

  type postApiV1NotificationDeliveriesIdRetryParams = {
    id: string;
  };

  type postApiV1NotificationsIdReadParams = {
    id: string;
  };

  type postApiV1ReservationsIdCancelParams = {
    id: string;
  };

  type postApiV1ReservationsIdCheckInParams = {
    id: string;
  };

  type postApiV1RoomApplicationsIdCancelParams = {
    id: string;
  };

  type postApiV1RoomApplicationsIdDecisionsParams = {
    id: string;
  };

  type postApiV1RoomsIdSeatsParams = {
    id: string;
  };

  type postApiV1UsersIdResetPasswordParams = {
    id: string;
  };

  type putApiV1RolesIdPermissionsParams = {
    id: string;
  };

  type putApiV1RoomsIdOpeningRulesParams = {
    id: string;
  };

  type putApiV1UsersIdRoleBindingsParams = {
    id: string;
  };

  type Reservation = {
    id: string;
    version: number;
    status:
      | "RESERVED"
      | "CHECKED_IN"
      | "CANCELLED"
      | "NO_SHOW"
      | "COMPLETED"
      | "INTERRUPTED";
    startAt: string;
    endAt: string;
    seatId?: string;
    roomId?: string;
    roomName?: string;
    seatCode?: string;
    allowedActions: ("cancel" | "check-in")[];
  };

  type ReservationCreate = {
    seatId: string;
    startAt: string;
    endAt: string;
  };

  type RoleBindingCommand = {
    expectedVersion: number;
    bindings: { roleId: string; scopes: RoleScope[] }[];
  };

  type RoleScope = {
    scopeType: "SELF" | "DEPARTMENT" | "ROOM" | "ALL" | "SHARED";
    departmentId?: string;
    roomId?: string;
  };

  type VersionCommand = {
    expectedVersion: number;
  };
}
