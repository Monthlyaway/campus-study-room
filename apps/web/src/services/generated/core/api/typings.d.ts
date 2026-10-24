declare namespace CoreAPI {
  type Application = {
    id: string;
    applicantId: string;
    applicantDepartmentId: string;
    roomId: string;
    startAt: string;
    endAt: string;
    purposeTitle: string;
    purposeDescription: string;
    status:
      | "PENDING"
      | "APPROVED"
      | "REJECTED"
      | "CANCELLED"
      | "EXPIRED"
      | "COMPLETED";
    currentStep: 1 | 2;
    expiresAt: string;
    cancelReason: string;
    configVersion: number;
    maxDurationHours: number;
    version: number;
    createdAt: string;
    updatedAt: string;
    roomName: string;
    roomCode: string;
    allowedActions: ("cancel" | "approve" | "reject")[];
  };

  type ApplicationCancelCommand = {
    expectedVersion: number;
    reason?: string;
  };

  type ApplicationCreate = {
    roomId: string;
    startAt: string;
    endAt: string;
    purposeTitle: string;
    purposeDescription: string;
  };

  type ApplicationDetail = {
    id: string;
    applicantId: string;
    applicantDepartmentId: string;
    roomId: string;
    startAt: string;
    endAt: string;
    purposeTitle: string;
    purposeDescription: string;
    status:
      | "PENDING"
      | "APPROVED"
      | "REJECTED"
      | "CANCELLED"
      | "EXPIRED"
      | "COMPLETED";
    currentStep: 1 | 2;
    expiresAt: string;
    cancelReason: string;
    configVersion: number;
    maxDurationHours: number;
    version: number;
    createdAt: string;
    updatedAt: string;
    roomName: string;
    roomCode: string;
    allowedActions: ("cancel" | "approve" | "reject")[];
    steps: ApprovalStep[];
    actions: ApprovalAction[];
  };

  type ApplicationDetailEnvelope = {
    data: ApplicationDetail;
    requestId: string;
  };

  type ApplicationEnvelope = {
    data: Application;
    requestId: string;
  };

  type ApplicationPage = {
    items: Application[];
    page: number;
    size: number;
    total: number;
  };

  type ApplicationPageEnvelope = {
    data: ApplicationPage;
    requestId: string;
  };

  type ApprovalAction = {
    stepNo: 1 | 2;
    actorId: string;
    actorName: string;
    decision: "APPROVE" | "REJECT";
    comment: string;
    occurredAt: string;
  };

  type ApprovalActionEnvelope = {
    data: ApprovalAction;
    requestId: string;
  };

  type ApprovalStep = {
    stepNo: 1 | 2;
    requiredPermission:
      | "room_application:review_dept"
      | "room_application:review_space";
    status: "WAITING" | "PENDING" | "APPROVED" | "REJECTED";
  };

  type ApprovalStepEnvelope = {
    data: ApprovalStep;
    requestId: string;
  };

  type AuditEvent = {
    id: string;
    actorType: "USER" | "JOB" | "SERVICE";
    actorId: string;
    action: string;
    objectType: string;
    objectId: string;
    roomId: string;
    sourceType: string;
    sourceId: string;
    /** Audit snapshot. The value is a JSON object, array, string, number, boolean or null; its shape depends on action. */
    beforeJson: any;
    /** Audit snapshot. The value is a JSON object, array, string, number, boolean or null; its shape depends on action. */
    afterJson: any;
    reason: string;
    requestId: string;
    createdAt: string;
  };

  type AuditEventEnvelope = {
    data: AuditEvent;
    requestId: string;
  };

  type AuditEventPage = {
    items: AuditEvent[];
    page: number;
    size: number;
    total: number;
  };

  type AuditEventPageEnvelope = {
    data: AuditEventPage;
    requestId: string;
  };

  type AvailableRoom = {
    id: string;
    code: string;
    name: string;
    enabled: boolean;
    version: number;
    buildingId: string;
    departmentId: string;
    buildingName: string;
    campusName: string;
    seatCount: number;
    roomName: string;
    campusId: string;
    available: boolean;
  };

  type AvailableRoomEnvelope = {
    data: AvailableRoom;
    requestId: string;
  };

  type AvailableRoomPage = {
    items: AvailableRoom[];
    page: number;
    size: number;
    total: number;
  };

  type AvailableRoomPageEnvelope = {
    data: AvailableRoomPage;
    requestId: string;
  };

  type AvailableSeat = {
    id: string;
    seatId: string;
    roomId: string;
    code: string;
    hasPower: boolean;
    roomName: string;
    roomCode: string;
    buildingName: string;
    campusName: string;
    buildingId: string;
    campusId: string;
    available: boolean;
  };

  type AvailableSeatEnvelope = {
    data: AvailableSeat;
    requestId: string;
  };

  type AvailableSeatPage = {
    items: AvailableSeat[];
    page: number;
    size: number;
    total: number;
  };

  type AvailableSeatPageEnvelope = {
    data: AvailableSeatPage;
    requestId: string;
  };

  type BookingLimits = {
    reservationMaxHours: number;
    applicationMaxHours: number;
  };

  type BookingLimitsEnvelope = {
    data: BookingLimits;
    requestId: string;
  };

  type Building = {
    id: string;
    code: string;
    name: string;
    enabled: boolean;
    version: number;
    campusId: string;
  };

  type BuildingCreate = {
    code: string;
    name: string;
    campusId: string;
  };

  type BuildingEnvelope = {
    data: Building;
    requestId: string;
  };

  type BuildingPage = {
    items: Building[];
    page: number;
    size: number;
    total: number;
  };

  type BuildingPageEnvelope = {
    data: BuildingPage;
    requestId: string;
  };

  type Campus = {
    id: string;
    code: string;
    name: string;
    enabled: boolean;
    version: number;
  };

  type CampusEnvelope = {
    data: Campus;
    requestId: string;
  };

  type CampusPage = {
    items: Campus[];
    page: number;
    size: number;
    total: number;
  };

  type CampusPageEnvelope = {
    data: CampusPage;
    requestId: string;
  };

  type CheckinCommand = {
    expectedVersion: number;
    /** Eight-character room code; surrounding whitespace is trimmed and letter case ignored. */
    roomCode: string;
  };

  type ConfigCommand = {
    expectedVersion: number;
    changes: {
      key: "M1_MAX_DURATION_HOURS" | "M2_MAX_DURATION_HOURS";
      value: number;
    }[];
  };

  type ConfigItem = {
    key: "M1_MAX_DURATION_HOURS" | "M2_MAX_DURATION_HOURS";
    value: number;
    version: number;
  };

  type ConfigItemEnvelope = {
    data: ConfigItem;
    requestId: string;
  };

  type Csrf = {
    csrfToken: string;
    headerName: "X-CSRF-TOKEN";
  };

  type CsrfEnvelope = {
    data: Csrf;
    requestId: string;
  };

  type CurrentUser = {
    id: string;
    username: string;
    schoolNo: string;
    displayName: string;
    email: string;
    departmentId: string;
    enabled: boolean;
    authVersion: number;
    version: number;
    createdAt: string;
    updatedAt: string;
    userId: string;
    permissions: string[];
    roles: string[];
    scopes: RoleScope[];
    bookingLimits: BookingLimits;
  };

  type CurrentUserEnvelope = {
    data: CurrentUser;
    requestId: string;
  };

  type DecisionCommand = {
    expectedVersion: number;
    decision: "APPROVE" | "REJECT";
    comment?: string;
  };

  type deleteApiV1RolesIdParams = {
    id: string;
  };

  type Department = {
    id: string;
    code: string;
    name: string;
    enabled: boolean;
    version: number;
    createdAt: string;
    updatedAt: string;
  };

  type DepartmentEnvelope = {
    data: Department;
    requestId: string;
  };

  type DepartmentPage = {
    items: Department[];
    page: number;
    size: number;
    total: number;
  };

  type DepartmentPageEnvelope = {
    data: DepartmentPage;
    requestId: string;
  };

  type DisplayCode = {
    localDate: string;
    code: string;
    expiresAt: string;
  };

  type DisplayCodeEnvelope = {
    data: DisplayCode;
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

  type getApiV1AvailabilityRoomsParams = {
    page?: number;
    size?: number;
    /** Offset timestamp; future whole-hour start in Asia/Shanghai. */
    startAt: string;
    /** Exclusive whole-hour end, at most 14 days ahead and within the configured duration. */
    endAt: string;
    campusId?: string;
    buildingId?: string;
    roomId?: string;
  };

  type getApiV1AvailabilitySeatsParams = {
    page?: number;
    size?: number;
    /** Offset timestamp; future whole-hour start in Asia/Shanghai. */
    startAt: string;
    /** Exclusive whole-hour end, at most 14 days ahead and within the configured duration. */
    endAt: string;
    campusId?: string;
    buildingId?: string;
    roomId?: string;
    hasPower?: boolean;
  };

  type getApiV1BuildingsParams = {
    page?: number;
    size?: number;
    campusId?: string;
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
    status?: "PENDING" | "LEASED" | "RETRYING" | "SENT";
    sourceType?: string;
    roomId?: string;
  };

  type getApiV1NotificationScopeRoomsParams = {
    page?: number;
    size?: number;
    /** Case-insensitive name or room code search. */
    q?: string;
  };

  type getApiV1NotificationsParams = {
    page?: number;
    size?: number;
    status?: "PENDING" | "LEASED" | "RETRYING" | "SENT";
    sourceType?: string;
    roomId?: string;
  };

  type getApiV1ReservationsIdParams = {
    id: string;
  };

  type getApiV1ReservationsParams = {
    page?: number;
    size?: number;
    status?:
      | "RESERVED"
      | "CHECKED_IN"
      | "CANCELLED"
      | "NO_SHOW"
      | "COMPLETED"
      | "INTERRUPTED";
    /** Inclusive reservation start lower bound. */
    startFrom?: string;
    /** Exclusive reservation start upper bound; must be later than startFrom. */
    startTo?: string;
  };

  type getApiV1RoleScopeRoomsParams = {
    page?: number;
    size?: number;
    /** Case-insensitive name or room code search. */
    q?: string;
  };

  type getApiV1RoomApplicationsIdParams = {
    id: string;
  };

  type getApiV1RoomApplicationsParams = {
    page?: number;
    size?: number;
    view?: "mine" | "pending-review" | "reviewed";
    status?:
      | "PENDING"
      | "APPROVED"
      | "REJECTED"
      | "CANCELLED"
      | "EXPIRED"
      | "COMPLETED";
  };

  type getApiV1RoomsIdAvailabilityParams = {
    id: string;
    /** Offset timestamp; future whole-hour start in Asia/Shanghai. */
    startAt: string;
    /** Exclusive whole-hour end, at most 14 days ahead and within the configured duration. */
    endAt: string;
  };

  type getApiV1RoomsIdDisplayCodeParams = {
    id: string;
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
    buildingId?: string;
    departmentId?: string;
  };

  type getApiV1StatisticsUsageParams = {
    fromDate: string;
    toDate: string;
    roomId?: string;
    departmentId?: string;
  };

  type getApiV1UsersParams = {
    page?: number;
    size?: number;
    /** Case-insensitive literal substring of username or displayName. */
    q?: string;
  };

  type getApiV1ViolationsParams = {
    page?: number;
    size?: number;
  };

  type getRoomParams = {
    id: string;
  };

  type LegacyPasswordResetResult = {
    updated: boolean;
  };

  type LegacyPasswordResetResultEnvelope = {
    data: LegacyPasswordResetResult;
    requestId: string;
  };

  type Login = {
    username: string;
    password: string;
  };

  type ManagedUser = {
    id: string;
    username: string;
    schoolNo: string;
    displayName: string;
    email: string;
    departmentId: string;
    enabled: boolean;
    authVersion: number;
    version: number;
    createdAt: string;
    updatedAt: string;
    roleBindings: RoleBinding[];
  };

  type ManagedUserEnvelope = {
    data: ManagedUser;
    requestId: string;
  };

  type ManagedUserPage = {
    items: ManagedUser[];
    page: number;
    size: number;
    total: number;
  };

  type ManagedUserPageEnvelope = {
    data: ManagedUserPage;
    requestId: string;
  };

  type MasterCreate = {
    code: string;
    name: string;
  };

  type MasterUpdate = {
    expectedVersion: number;
    name?: string;
    enabled?: boolean;
  };

  type Notification = {
    id: string;
    recipientId: string;
    roomId: string;
    eventType: string;
    sourceType: string;
    sourceId: string;
    dedupKey: string;
    /** Event-specific, allowlisted notification text and source identifiers. */
    payloadJson: Record<string, any>;
    channel: "EMAIL";
    dueAt: string;
    status: "PENDING" | "LEASED" | "RETRYING" | "SENT";
    attempts: number;
    nextAttemptAt: string;
    leaseOwner: string;
    leaseUntil: string;
    sentAt: string;
    readAt: string;
    lastErrorCode: string;
    version: number;
    createdAt: string;
  };

  type NotificationEnvelope = {
    data: Notification;
    requestId: string;
  };

  type NotificationPage = {
    items: Notification[];
    page: number;
    size: number;
    total: number;
  };

  type NotificationPageEnvelope = {
    data: NotificationPage;
    requestId: string;
  };

  type OpeningRule = {
    weekday: number;
    startMinute: number;
    endMinute: number;
  };

  type OpeningRuleEnvelope = {
    data: OpeningRule;
    requestId: string;
  };

  type OpeningRulesCommand = {
    expectedVersion: number;
    rules: OpeningRule[];
  };

  type PasswordResetCommand = {
    expectedVersion: number;
    /** UTF-8 encoding must be at most 72 bytes. */
    newPassword: string;
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

  type Permission = {
    id: string;
    code: string;
    description: string;
  };

  type PermissionEnvelope = {
    data: Permission;
    requestId: string;
  };

  type PermissionPage = {
    items: Permission[];
    page: number;
    size: number;
    total: number;
  };

  type PermissionPageEnvelope = {
    data: PermissionPage;
    requestId: string;
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
    userId: string;
    roomId: string;
    seatId: string;
    startAt: string;
    endAt: string;
    status:
      | "RESERVED"
      | "CHECKED_IN"
      | "CANCELLED"
      | "NO_SHOW"
      | "COMPLETED"
      | "INTERRUPTED";
    checkedInAt: string;
    cancelReason: string;
    endedAt: string;
    configVersion: number;
    maxDurationHours: number;
    version: number;
    createdAt: string;
    updatedAt: string;
    roomName: string;
    roomCode: string;
    hasPower: boolean;
    seatCode: string;
    allowedActions: ("cancel" | "check-in")[];
    operatorNote?: string;
  };

  type ReservationCreate = {
    seatId: string;
    startAt: string;
    endAt: string;
  };

  type ReservationEnvelope = {
    data: Reservation;
    requestId: string;
  };

  type ReservationPage = {
    items: Reservation[];
    page: number;
    size: number;
    total: number;
  };

  type ReservationPageEnvelope = {
    data: ReservationPage;
    requestId: string;
  };

  type ResourceClaim = {
    id: string;
    roomId: string;
    seatId: string;
    startAt: string;
    endAt: string;
    sourceType: "M1" | "M2" | "M3";
    sourceId: string;
    sourceVersion: number;
    releasedAt: string;
    createdBy: string;
    createdRequestId: string;
    releaseRequestId: string;
  };

  type ResourceClaimEnvelope = {
    data: ResourceClaim;
    requestId: string;
  };

  type ResourceClaimPage = {
    items: ResourceClaim[];
    page: number;
    size: number;
    total: number;
  };

  type ResourceClaimPageEnvelope = {
    data: ResourceClaimPage;
    requestId: string;
  };

  type Role = {
    id: string;
    code: string;
    name: string;
    enabled: boolean;
    version: number;
  };

  type RoleBinding = {
    id: string;
    roleId: string;
    scopes: RoleScope[];
  };

  type RoleBindingCommand = {
    expectedVersion: number;
    bindings: { roleId: string; scopes: RoleScope[] }[];
  };

  type RoleBindingEnvelope = {
    data: RoleBinding;
    requestId: string;
  };

  type RoleBindingsResult = {
    id: string;
    roleBindings: RoleBinding[];
    version: number;
  };

  type RoleBindingsResultEnvelope = {
    data: RoleBindingsResult;
    requestId: string;
  };

  type RoleCreate = {
    code: string;
    name: string;
  };

  type RoleDisabled = {
    disabled: boolean;
  };

  type RoleDisabledEnvelope = {
    data: RoleDisabled;
    requestId: string;
  };

  type RoleEnvelope = {
    data: Role;
    requestId: string;
  };

  type RolePermissionsCommand = {
    expectedVersion: number;
    permissionCodes: string[];
  };

  type RoleScope = {
    scopeType: "SELF" | "DEPARTMENT" | "ROOM" | "ALL" | "SHARED";
    departmentId?: string;
    roomId?: string;
    /** Display name for an existing authorization target; never use as an identifier. */
    roomName?: string;
    /** Display name for an existing authorization target; never use as an identifier. */
    departmentName?: string;
  };

  type RoleScopeEnvelope = {
    data: RoleScope;
    requestId: string;
  };

  type RoleScopeRoom = {
    id: string;
    code: string;
    name: string;
    enabled: boolean;
    buildingName: string;
    departmentName: string;
  };

  type RoleScopeRoomPage = {
    items: RoleScopeRoom[];
    page: number;
    size: number;
    total: number;
  };

  type RoleScopeRoomPageEnvelope = {
    data: RoleScopeRoomPage;
    requestId: string;
  };

  type RoleWithPermissions = {
    id: string;
    code: string;
    name: string;
    enabled: boolean;
    version: number;
    permissionCodes: string[];
  };

  type RoleWithPermissionsEnvelope = {
    data: RoleWithPermissions;
    requestId: string;
  };

  type RoleWithPermissionsPage = {
    items: RoleWithPermissions[];
    page: number;
    size: number;
    total: number;
  };

  type RoleWithPermissionsPageEnvelope = {
    data: RoleWithPermissionsPage;
    requestId: string;
  };

  type Room = {
    id: string;
    code: string;
    name: string;
    enabled: boolean;
    version: number;
    buildingId: string;
    departmentId: string;
  };

  type RoomAvailability = {
    roomId: string;
    startAt: string;
    endAt: string;
    seats: SeatAvailability[];
  };

  type RoomAvailabilityEnvelope = {
    data: RoomAvailability;
    requestId: string;
  };

  type RoomCreate = {
    code: string;
    name: string;
    buildingId: string;
    departmentId?: string;
  };

  type RoomDetail = {
    id: string;
    code: string;
    name: string;
    enabled: boolean;
    version: number;
    buildingId: string;
    departmentId: string;
    buildingName: string;
    campusName: string;
    seatCount: number;
    openingRules: OpeningRule[];
  };

  type RoomDetailEnvelope = {
    data: RoomDetail;
    requestId: string;
  };

  type RoomEnvelope = {
    data: Room;
    requestId: string;
  };

  type RoomSummary = {
    id: string;
    code: string;
    name: string;
    enabled: boolean;
    version: number;
    buildingId: string;
    departmentId: string;
    buildingName: string;
    campusName: string;
    seatCount: number;
  };

  type RoomSummaryEnvelope = {
    data: RoomSummary;
    requestId: string;
  };

  type RoomSummaryPage = {
    items: RoomSummary[];
    page: number;
    size: number;
    total: number;
  };

  type RoomSummaryPageEnvelope = {
    data: RoomSummaryPage;
    requestId: string;
  };

  type Seat = {
    id: string;
    roomId: string;
    code: string;
    hasPower: boolean;
    enabled: boolean;
    version: number;
  };

  type SeatAvailability = {
    id: string;
    roomId: string;
    code: string;
    hasPower: boolean;
    enabled: boolean;
    version: number;
    seatId: string;
    available: boolean;
    unavailableReason:
      | "DISABLED"
      | "OUTSIDE_OPENING_HOURS"
      | "USER_CONFLICT"
      | "OCCUPIED";
  };

  type SeatAvailabilityEnvelope = {
    data: SeatAvailability;
    requestId: string;
  };

  type SeatCreate = {
    code: string;
    hasPower?: boolean;
  };

  type SeatEnvelope = {
    data: Seat;
    requestId: string;
  };

  type SeatPage = {
    items: Seat[];
    page: number;
    size: number;
    total: number;
  };

  type SeatPageEnvelope = {
    data: SeatPage;
    requestId: string;
  };

  type SeatUpdate = {
    expectedVersion: number;
    hasPower?: boolean;
    enabled?: boolean;
  };

  type ServiceVersion = {
    releaseId: string;
    commit: string;
    service: "core-service";
  };

  type ServiceVersionEnvelope = {
    data: ServiceVersion;
    requestId: string;
  };

  type SystemConfig = {
    version: number;
    items: ConfigItem[];
  };

  type SystemConfigEnvelope = {
    data: SystemConfig;
    requestId: string;
  };

  type UsageStatistics = {
    reservationCount: number;
    cancelledCount: number;
    noShowCount: number;
    checkinDenominator: number;
    checkinNumerator: number;
    reservedSeatHours: number;
    checkinRate: number;
    fromDate: string;
    toDate: string;
    timezone: "Asia/Shanghai";
  };

  type UsageStatisticsEnvelope = {
    data: UsageStatistics;
    requestId: string;
  };

  type User = {
    id: string;
    username: string;
    schoolNo: string;
    displayName: string;
    email: string;
    departmentId: string;
    enabled: boolean;
    authVersion: number;
    version: number;
    createdAt: string;
    updatedAt: string;
  };

  type UserCreate = {
    username: string;
    /** UTF-8 encoding must be at most 72 bytes. */
    password: string;
    email: string;
    displayName: string;
    departmentId: string;
    schoolNo?: string;
  };

  type UserEnvelope = {
    data: User;
    requestId: string;
  };

  type UserUpdate = {
    expectedVersion: number;
    enabled?: boolean;
    email?: string;
    displayName?: string;
    departmentId?: string;
  };

  type VersionCommand = {
    expectedVersion: number;
  };

  type Violation = {
    id: string;
    reservationId: string;
    userId: string;
    reason: "NO_SHOW";
    occurredAt: string;
  };

  type ViolationEnvelope = {
    data: Violation;
    requestId: string;
  };

  type ViolationPage = {
    items: Violation[];
    page: number;
    size: number;
    total: number;
  };

  type ViolationPageEnvelope = {
    data: ViolationPage;
    requestId: string;
  };
}
