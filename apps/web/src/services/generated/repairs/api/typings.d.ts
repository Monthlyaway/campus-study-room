declare namespace RepairAPI {
  type acceptanceRepairParams = {
    id: string;
  };

  type assignRepairParams = {
    id: string;
  };

  type cancelRepairParams = {
    id: string;
  };

  type completeRepairParams = {
    id: string;
  };

  type getRepairParams = {
    id: string;
  };

  type listAssignableWorkersParams = {
    roomId: string;
    page?: number;
    size?: number;
  };

  type listRepairAuditEventsParams = {
    action?: string;
    objectId?: string;
    roomId?: string;
    from?: string;
    to?: string;
    page?: number;
    size?: number;
  };

  type listRepairsParams = {
    view?: "mine" | "assigned" | "manage";
    status?:
      | "SUBMITTED"
      | "ASSIGNED"
      | "BLOCK_PENDING"
      | "IN_REPAIR"
      | "AWAITING_ACCEPTANCE"
      | "RELEASE_PENDING"
      | "CLOSED"
      | "CANCELLED";
    roomId?: string;
    page?: number;
    size?: number;
  };

  type ratingRepairParams = {
    id: string;
  };

  type RepairAcceptance = {
    id?: string;
    roundNo?: number;
    actorId?: string;
    decision?: "ACCEPT" | "REJECT";
    comment?: any;
    createdAt?: string;
  };

  type RepairAcceptanceRequest = {
    expectedVersion: number;
    decision: "ACCEPT" | "REJECT";
    comment?: string;
  };

  type RepairAssign = {
    expectedVersion: number;
    assignedTo: string;
  };

  type RepairCancel = {
    expectedVersion: number;
    reason?: string;
  };

  type RepairComplete = {
    expectedVersion: number;
    workNote: string;
  };

  type RepairCreate = {
    roomId: string;
    title: string;
    description: string;
  };

  type RepairDetail =
    // #/components/schemas/RepairOrder
    RepairOrder & {
      history?: RepairHistory[];
      acceptances?: RepairAcceptance[];
      rating?: RepairRating | null;
      sync?: RepairSync;
    };

  type RepairError = {
    error: { code: string; message: string; details: Record<string, any> };
    requestId: string;
  };

  type RepairExpectedVersion = {
    expectedVersion: number;
  };

  type RepairHistory = {
    id?: string;
    fromStatus?: RepairStatus | null;
    toStatus?: RepairStatus;
    actorId?: any;
    reason?: any;
    oldAssignee?: any;
    newAssignee?: any;
    requestId?: string;
    createdAt?: string;
  };

  type RepairOrder = {
    id: string;
    reporterId: string;
    roomId: string;
    title: string;
    description: string;
    status: RepairStatus;
    assignedTo?: any;
    assignedBy?: any;
    acceptanceRound?: number;
    lastWorkNote?: any;
    resourceVersion?: number;
    version: number;
    allowedActions: (
      | "assign"
      | "start"
      | "complete"
      | "accept"
      | "reject"
      | "cancel"
      | "rate"
      | "retry-sync"
    )[];
    submittedAt: string;
    firstAssignedAt?: any;
    assignedAt?: any;
    workCompletedAt?: any;
    acceptedAt?: any;
    closedAt?: any;
    assignDueAt: string;
    workDueAt?: any;
    acceptDueAt?: any;
    createdAt?: string;
    updatedAt?: string;
  };

  type RepairOrderPage = {
    items: RepairOrder[];
    page: number;
    size: number;
    total: number;
  };

  type RepairRating = {
    score: number;
    comment?: any;
    createdAt: string;
  };

  type RepairRatingRequest = {
    expectedVersion: number;
    score: number;
    comment?: string;
  };

  type RepairStatus =
    | "SUBMITTED"
    | "ASSIGNED"
    | "BLOCK_PENDING"
    | "IN_REPAIR"
    | "AWAITING_ACCEPTANCE"
    | "RELEASE_PENDING"
    | "CLOSED"
    | "CANCELLED";

  type RepairSync = {
    pending: boolean;
    attempts: number;
    lastErrorCode?: any;
    lastAttemptAt?: any;
    nextAttemptAt?: any;
    needsAttention: boolean;
  };

  type RepairWorker = {
    userId: string;
    displayName: string;
  };

  type RepairWorkerPage = {
    items: RepairWorker[];
    page: number;
    size: number;
    total: number;
  };

  type retrySyncRepairParams = {
    id: string;
  };

  type startRepairParams = {
    id: string;
  };
}
