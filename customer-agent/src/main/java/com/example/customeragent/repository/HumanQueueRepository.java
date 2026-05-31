package com.example.customeragent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.customeragent.model.HumanQueueItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HumanQueueRepository extends BaseMapper<HumanQueueItem> {
}
