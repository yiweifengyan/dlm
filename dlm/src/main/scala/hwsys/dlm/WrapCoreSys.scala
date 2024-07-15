

// deal at system level
val rd_req = slave Stream Bits(96 bits)
val wr_req = slave Stream Bits(96 bits)
val sq = master Stream Bits(544 bits)
val ack = slave Stream Bits(43 bits)